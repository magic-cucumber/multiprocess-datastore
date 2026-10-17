@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.datastore.multiprocess.util

import top.kagg886.datastore.multiprocess.util.NativeLogger.Companion.log
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.VERBOSE
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.DEBUG
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.INFO
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.WARN
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.ERROR

import kotlinx.cinterop.*
import okio.IOException
import okio.Path
import platform.Foundation.NSCondition
import platform.darwin.*
import platform.posix.*
import kotlin.concurrent.atomics.AtomicBoolean

internal actual fun platformFileChangedListenerCreate(
    file: Path,
    listener: InternalFileChangeListener,
): FileChangeListenerHandle = IosFileChangeListener(file, listener).also { it.start() }

private val vnodeEvents = DISPATCH_VNODE_WRITE.toULong() or DISPATCH_VNODE_EXTEND.toULong() or
    DISPATCH_VNODE_ATTRIB.toULong() or DISPATCH_VNODE_DELETE.toULong() or
    DISPATCH_VNODE_RENAME.toULong() or DISPATCH_VNODE_REVOKE.toULong()

private class IosFileWatch(
    val owner: IosFileChangeListener,
    val source: dispatch_source_t,
    val fd: Int,
    val directory: Boolean,
    val identity: Pair<Long, ULong>,
)

private fun startIosFileListener(context: COpaquePointer?) {
    context!!.asStableRef<IosFileChangeListener>().get().initializeOnQueue()
}
private fun checkIosFileListener(context: COpaquePointer?) {
    context!!.asStableRef<IosFileChangeListener>().get().checkOnQueue()
}
private fun cancelIosFileListener(context: COpaquePointer?) {
    context!!.asStableRef<IosFileChangeListener>().get().cancelOnQueue()
}
private fun changedIosFileWatch(context: COpaquePointer?) {
    val watch = context!!.asStableRef<IosFileWatch>().get()
    watch.owner.eventOnQueue(watch)
}
private fun cancelledIosFileWatch(context: COpaquePointer?) {
    val reference = context!!.asStableRef<IosFileWatch>()
    val watch = reference.get()
    if (close(watch.fd) != 0) {
        val error = errno
        log(ERROR, "FileListener.ios", "Watch close fd=${watch.fd} directory=${watch.directory} failed errno=$error")
    } else log(DEBUG, "FileListener.ios", "Watch closed fd=${watch.fd} directory=${watch.directory}")
    // GCD objects are Objective-C references managed by Kotlin/Native's ARC integration.
    reference.dispose()
    watch.owner.watchReleasedOnQueue()
}

/** GCD sources share system workers; no pthread, timer, or polling loop is created. */
private class IosFileChangeListener(
    private val file: Path,
    private val listener: InternalFileChangeListener,
) : FileChangeListenerHandle {
    override val released = AtomicBoolean(false)
    private val cleanupScheduled = AtomicBoolean(false)
    private val condition = NSCondition()
    private var queue: dispatch_queue_t? = null
    private var context: StableRef<IosFileChangeListener>? = null
    private var finished = false // Protected by condition.
    private var startupError: Throwable? = null
    // Remaining fields belong exclusively to the serial GCD queue.
    private var directory: IosFileWatch? = null
    private var target: IosFileWatch? = null
    private var activeWatches = 0 // Includes retired sources waiting for cancellation.
    private var baseline: Metadata? = null
    private var terminated = false

    private data class Metadata(
        val device: Long, val inode: ULong, val size: Long,
        val modifiedSeconds: Long, val modifiedNanos: Long,
        val changedSeconds: Long, val changedNanos: Long,
    ) {
        val identity: Pair<Long, ULong> get() = device to inode
    }

    fun start() {
        log(DEBUG, "FileListener.ios", "Start requested path=$file")
        try {
            queue = dispatch_queue_create("datastore-file-listener", null)
                ?: throw IOException("Unable to allocate file listener queue")
            log(DEBUG, "FileListener.ios", "Serial queue created path=$file")
            context = StableRef.create(this)
            val pointer = context!!.asCPointer()
            dispatch_queue_set_specific(queue, pointer, pointer, null)
            dispatch_sync_f(queue, pointer, staticCFunction(::startIosFileListener))
            startupError?.let { throw it }
        } catch (error: Throwable) {
            log(ERROR, "FileListener.ios", "Start failed path=$file: $error")
            if (context == null) {
                queue = null
                released.store(true)
            } else release()
            throw error
        }
    }

    private fun fail(operation: String): Nothing = throw IOException("$operation failed: $errno ($file)")
    private fun metadata(): Metadata? = memScoped {
        val value = alloc<stat>()
        if (lstat(file.toString(), value.ptr) != 0) {
            if (errno == ENOENT) return@memScoped null
            fail("lstat")
        }
        if (value.st_mode.toInt() and S_IFMT.toInt() != S_IFREG.toInt()) {
            throw IOException("Listener target is no longer a regular file: $file")
        }
        Metadata(value.st_dev.toLong(), value.st_ino.toULong(), value.st_size,
            value.st_mtimespec.tv_sec, value.st_mtimespec.tv_nsec,
            value.st_ctimespec.tv_sec, value.st_ctimespec.tv_nsec)
    }

    /** Owns fd on entry, including failure paths. */
    private fun watch(fd: Int, isDirectory: Boolean): IosFileWatch {
        var source: dispatch_source_t? = null
        var reference: StableRef<IosFileWatch>? = null
        try {
            val identity = memScoped {
                val value = alloc<stat>()
                if (fstat(fd, value.ptr) != 0) fail("fstat")
                val kind = value.st_mode.toInt() and S_IFMT.toInt()
                if (kind != (if (isDirectory) S_IFDIR.toInt() else S_IFREG.toInt())) {
                    throw IOException("Invalid file listener target: $file")
                }
                value.st_dev.toLong() to value.st_ino.toULong()
            }
            source = dispatch_source_create(_dispatch_source_type_vnode.ptr, fd.toULong(), vnodeEvents, queue)
                ?: throw IOException("Unable to allocate file listener source")
            val value = IosFileWatch(this, source, fd, isDirectory, identity)
            reference = StableRef.create(value)
            dispatch_set_context(source, reference.asCPointer())
            dispatch_source_set_event_handler_f(source, staticCFunction(::changedIosFileWatch))
            dispatch_source_set_cancel_handler_f(source, staticCFunction(::cancelledIosFileWatch))
            activeWatches++
            log(DEBUG, "FileListener.ios", "Watch created path=$file fd=$fd directory=$isDirectory identity=$identity active=$activeWatches")
            dispatch_resume(source)
            return value
        } catch (error: Throwable) {
            // No active handler exists yet. Dispose an unactivated source on startup failure.
            source?.let { dispatch_source_cancel(it); dispatch_resume(it) }
            reference?.dispose()
            log(DEBUG, "FileListener.ios", "Rollback unactivated watch path=$file fd=$fd")
            if (close(fd) != 0) {
                val failure = errno
                log(ERROR, "FileListener.ios", "Rollback close path=$file fd=$fd failed errno=$failure")
            }
            throw error
        }
    }

    private fun openWatch(path: Path, isDirectory: Boolean): IosFileWatch? {
        val fd = open(path.toString(), O_EVTONLY or O_CLOEXEC or O_NOFOLLOW)
        if (fd < 0) {
            if (errno == ENOENT && !isDirectory) return null
            fail("open")
        }
        log(DEBUG, "FileListener.ios", "Opened watch path=$path fd=$fd directory=$isDirectory")
        return watch(fd, isDirectory)
    }
    private fun retire(watch: IosFileWatch?) {
        if (watch != null) {
            log(DEBUG, "FileListener.ios", "Retire watch path=$file fd=${watch.fd} directory=${watch.directory}")
            dispatch_source_cancel(watch.source)
        }
    }
    private fun rebind(): Metadata? {
        repeat(3) { attempt ->
            val state = metadata() ?: return null
            if (target?.identity == state.identity) return state
            if (target != null) log(WARN, "FileListener.ios", "Target identity changed path=$file before=${target?.identity} after=${state.identity}")
            log(DEBUG, "FileListener.ios", "Bind target path=$file attempt=${attempt + 1}")
            retire(target)
            target = null
            target = openWatch(file, false)
            val after = metadata() ?: return null
            if (target?.identity == after.identity) return after
        }
        throw IOException("Listener target kept changing while rebinding: $file")
    }

    fun initializeOnQueue() {
        try {
            val parent = file.parent ?: throw IOException("Listener file has no parent: $file")
            directory = openWatch(parent, true)
            baseline = metadata() ?: throw IOException("Listener file disappeared during startup: $file")
            log(DEBUG, "FileListener.ios", "Baseline path=$file metadata=$baseline")
            rebind() ?: throw IOException("Listener file disappeared during startup: $file")
            log(INFO, "FileListener.ios", "Started path=$file active_watches=$activeWatches")
            log(DEBUG, "FileListener.ios", "Schedule registration reconciliation path=$file")
            // One reconciliation covers registration gaps; no periodic polling.
            dispatch_async_f(queue, context!!.asCPointer(), staticCFunction(::checkIosFileListener))
        } catch (error: Throwable) { startupError = error }
    }
    fun eventOnQueue(watch: IosFileWatch) {
        if (released.load()) return
        val flags = dispatch_source_get_data(watch.source).toULong()
        log(VERBOSE, "FileListener.ios", "Vnode event path=$file fd=${watch.fd} directory=${watch.directory} flags=$flags")
        val invalidated = DISPATCH_VNODE_DELETE.toULong() or DISPATCH_VNODE_RENAME.toULong() or DISPATCH_VNODE_REVOKE.toULong()
        if (watch.directory && flags and invalidated != 0uL) {
            log(WARN, "FileListener.ios", "Directory invalidated path=$file flags=$flags")
            terminateOnQueue()
        } else checkOnQueue()
    }
    fun checkOnQueue() {
        if (released.load()) return
        try {
            // An old vnode's DELETE may mean replacement; inspect the current path first.
            val next = rebind()
            if (next == null) {
                log(WARN, "FileListener.ios", "Target disappeared path=$file; stopping observation")
                terminateOnQueue(); return
            }
            val changed = baseline != next
            log(VERBOSE, "FileListener.ios", "Metadata comparison path=$file changed=$changed before=$baseline after=$next")
            baseline = next
            if (changed && !released.load()) {
                log(DEBUG, "FileListener.ios", "Deliver content change path=$file")
                runCatching { listener.onFileContentChanged() }.onFailure {
                    log(ERROR, "FileListener.ios", "Content callback failed path=$file: $it")
                }
            }
        } catch (error: Throwable) {
            log(ERROR, "FileListener.ios", "Event processing failed path=$file: $error")
            terminateOnQueue()
        }
    }
    private fun terminateOnQueue() {
        if (!released.compareAndSet(false, true)) return
        log(DEBUG, "FileListener.ios", "Termination accepted path=$file")
        terminated = true
        cleanupScheduled.store(true)
        cancelOnQueue()
    }
    fun cancelOnQueue() {
        log(DEBUG, "FileListener.ios", "Cancel watches path=$file active=$activeWatches")
        retire(directory)
        directory = null
        retire(target)
        target = null
        if (activeWatches == 0) finishOnQueue()
    }
    fun watchReleasedOnQueue() {
        activeWatches--
        log(DEBUG, "FileListener.ios", "Cancellation callback path=$file remaining=$activeWatches")
        if (activeWatches == 0 && released.load()) finishOnQueue()
    }
    private fun finishOnQueue() {
        condition.lock()
        try {
            if (finished) return
            val nativeQueue = queue
            val nativeContext = context
            if (nativeContext != null) dispatch_queue_set_specific(nativeQueue, nativeContext.asCPointer(), null, null)
            context = null
            queue = null
            nativeContext?.dispose()
            log(DEBUG, "FileListener.ios", "Disposal complete path=$file terminated=$terminated")
            finished = true
            condition.broadcast()
        } finally { condition.unlock() }
        // Fds are closed and owned references cleared; GCD object reclamation is automatic.
        if (terminated) {
            log(DEBUG, "FileListener.ios", "Deliver termination path=$file")
            runCatching { listener.onListenerTerminated() }.onFailure {
                log(ERROR, "FileListener.ios", "Termination callback failed path=$file: $it")
            }
        }
    }

    override fun release() {
        released.store(true)
        condition.lock()
        try {
            if (finished) return
            val pointer = context?.asCPointer() ?: return
            val inCallback = dispatch_get_specific(pointer) == pointer
            if (cleanupScheduled.compareAndSet(false, true)) {
                log(DEBUG, "FileListener.ios", "Schedule cancellation path=$file")
                dispatch_async_f(queue, pointer, staticCFunction(::cancelIosFileListener))
            }
            // GCD cancellation handlers run only after the event callback returns.
            log(DEBUG, "FileListener.ios", "Release wait path=$file inCallback=$inCallback")
            if (!inCallback) while (!finished) condition.wait()
        } finally { condition.unlock() }
    }
}
