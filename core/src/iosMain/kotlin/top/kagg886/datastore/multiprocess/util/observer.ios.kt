@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.datastore.multiprocess.util

import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path
import platform.Foundation.NSCondition
import platform.darwin.*
import platform.posix.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.time.TimeSource

internal actual object InternalDictionaryObserver {
    actual fun observe(path: Path, block: DictionaryObserverListener): AutoCloseable =
        IosDirectoryObservation(path, block).also { it.start() }
}

private fun directoryObserverThread(context: COpaquePointer?): COpaquePointer? {
    val reference = context!!.asStableRef<IosDirectoryObservation>()
    try { reference.get().run() } finally { reference.dispose() }
    return null
}

/** One detached pthread owns all descriptors, snapshots and listener delivery. */
private class IosDirectoryObservation(
    private val path: Path,
    private val listener: DictionaryObserverListener,
) : AutoCloseable {
    private data class Identity(val device: Long, val inode: ULong)
    private data class WatchedFile(val fd: Int, val identity: Identity)
    private val state = AtomicInt(0) // active, terminated, closed
    private val condition = NSCondition()
    private var thread: pthread_t? = null
    private var initialized = false
    private var enabled = false
    private var finished = false
    private var startupError: DictionaryObserverTerminatedException? = null
    private var queue = -1
    private var directory = -1
    private val files = mutableMapOf<Path, WatchedFile>()
    private val snapshot = DirectorySnapshot(path)

    fun start() {
        val reference = StableRef.create(this)
        val error = memScoped {
            val result = alloc<pthread_tVar>()
            val code = pthread_create(result.ptr, null, staticCFunction(::directoryObserverThread), reference.asCPointer())
            if (code == 0) thread = result.value
            code
        }
        if (error != 0) {
            reference.dispose()
            throw DictionaryObserverTerminatedException(error, path)
        }
        condition.lock()
        try {
            while (!initialized && !finished) condition.wait()
            startupError?.let { throw it }
            enabled = true
            condition.broadcast()
        } finally { condition.unlock() }
    }

    fun run() {
        // Detached threads reclaim their native resources even when listeners close from this thread.
        pthread_detach(pthread_self())
        var failure: DictionaryObserverTerminatedException? = null
        var started = false
        try {
            directory = open(path.toString(), O_EVTONLY or O_CLOEXEC or O_NOFOLLOW)
            if (directory < 0) fail(errno)
            queue = kqueue()
            if (queue < 0) fail(errno)
            val original = identity(path, true) ?: fail(ENOENT)
            val registrationError = register(directory)
            if (registrationError != 0) fail(registrationError)
            snapshot.initialize()
            refreshWatches()
            condition.lock()
            try {
                initialized = true
                condition.broadcast()
                while (!enabled) condition.wait()
            } finally { condition.unlock() }
            started = true
            loop(original)
        } catch (error: Throwable) {
            failure = if (error is DictionaryObserverTerminatedException) error else
                DictionaryObserverTerminatedException.internal(path, error)
        } finally {
            files.values.forEach { close(it.fd) }
            files.clear()
            if (directory >= 0) { close(directory); directory = -1 }
            if (queue >= 0) { close(queue); queue = -1 }
            if (!started) {
                condition.lock()
                try { startupError = failure } finally { condition.unlock() }
            } else {
                val termination = failure
                if (termination != null && state.compareAndSet(0, 1)) {
                    runCatching { listener.onTerminated(termination) }
                }
            }
            condition.lock()
            try { finished = true; condition.broadcast() } finally { condition.unlock() }
        }
    }

    private fun loop(original: Identity) = memScoped {
        val events = allocArray<kevent>(256)
        val timeout = alloc<timespec>().apply { tv_sec = 0; tv_nsec = 100_000_000 }
        var lastScan = TimeSource.Monotonic.markNow()
        var retry = true // Scan once after file watchers are armed to cover initial registration gaps.
        var failures = 0
        while (state.load() == 0) {
            val count = kevent(queue, null, 0, events, 256, timeout.ptr)
            if (count < 0) { if (errno == EINTR) continue else fail(errno) }
            if (state.load() != 0) break
            val periodic = lastScan.elapsedNow().inWholeMilliseconds >= 1000
            var fullScan = periodic || retry
            val candidates = mutableSetOf<Path>()
            for (index in 0 until count) {
                val event = events[index]
                if (event.flags.toInt() and EV_ERROR.toInt() != 0) fail(event.data.toInt())
                val fd = event.ident.toInt()
                if (fd == directory) {
                    if (event.fflags.toInt() and (NOTE_DELETE.toInt() or NOTE_RENAME.toInt() or NOTE_REVOKE.toInt()) != 0) {
                        fail(ENOENT)
                    }
                    fullScan = true
                } else {
                    files.entries.firstOrNull { it.value.fd == fd }?.let { candidates.add(it.key) }
                }
            }
            if (periodic && identity(path, true) != original) fail(ENOENT)
            if (!fullScan && candidates.isEmpty()) continue
            try {
                // Rebind before scanning; the subsequent scan covers the registration gap.
                refreshWatches()
                val changes = snapshot.diff(candidates, fullScan)
                retry = false
                failures = 0
                if (fullScan) lastScan = TimeSource.Monotonic.markNow()
                if (changes.isNotEmpty() && state.load() == 0) runCatching { listener.onEvent(changes) }
            } catch (error: Throwable) {
                if (error is DictionaryObserverTerminatedException || ++failures >= 5) throw error
                retry = true
            }
        }
    }

    private fun identity(target: Path, isDirectory: Boolean = false): Identity? = memScoped {
        val info = alloc<stat>()
        if (lstat(target.toString(), info.ptr) != 0) {
            if (errno == ENOENT) return@memScoped null
            fail(errno)
        }
        val kind = info.st_mode.toInt() and S_IFMT.toInt()
        if (kind != (if (isDirectory) S_IFDIR.toInt() else S_IFREG.toInt())) return@memScoped null
        Identity(info.st_dev.toLong(), info.st_ino.toULong())
    }

    private fun register(fd: Int): Int = memScoped {
        val event = alloc<kevent>().apply {
            ident = fd.toULong()
            filter = EVFILT_VNODE.toShort()
            flags = (EV_ADD.toInt() or EV_CLEAR.toInt()).toUShort()
            fflags = (NOTE_WRITE.toInt() or NOTE_EXTEND.toInt() or NOTE_ATTRIB.toInt() or
                NOTE_DELETE.toInt() or NOTE_RENAME.toInt() or NOTE_REVOKE.toInt()).toUInt()
            data = 0
            udata = null
        }
        val result = kevent(queue, event.ptr, 1, null, 0, null)
        if (result < 0) errno else 0
    }

    private fun refreshWatches() {
        val current = FileSystem.SYSTEM.list(path).mapNotNull { target ->
            identity(target)?.let { target to it }
        }.toMap()
        val obsolete = files.filter { (target, value) -> current[target] != value.identity }.keys
        obsolete.forEach { target -> files.remove(target)?.let { close(it.fd) } }
        for ((target, identity) in current) {
            if (target in files || files.size >= 1024) continue // Larger directories retain periodic scan coverage.
            val fd = open(target.toString(), O_EVTONLY or O_CLOEXEC or O_NOFOLLOW)
            if (fd < 0) {
                if (errno == ENOENT || errno == EMFILE || errno == ENFILE || errno == ENOMEM) continue
                fail(errno)
            }
            val error = register(fd)
            if (error == 0) files[target] = WatchedFile(fd, identity) else {
                close(fd)
                if (error != ENOMEM && error != ENOSPC) fail(error)
            }
        }
    }

    private fun fail(code: Int): Nothing = throw DictionaryObserverTerminatedException(code, path)

    override fun close() {
        state.compareAndSet(0, 2)
        val nativeThread = thread
        if (nativeThread != null && pthread_equal(pthread_self(), nativeThread) != 0) return
        condition.lock()
        try { while (!finished) condition.wait() } finally { condition.unlock() }
    }
}
