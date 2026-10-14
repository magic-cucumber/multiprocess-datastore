package top.kagg886.datastore.multiprocess.util

import okio.Path
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.atomics.AtomicBoolean

internal actual fun platformFileChangedListenerCreate(
    file: Path,
    listener: InternalFileChangeListener,
): FileChangeListenerHandle {
    NativeLibrary.ensureNativeLibraryInited()
    return JvmFileChangeListener(file, listener).also { it.start() }
}

private val fileListenerCallbackDepth = ThreadLocal.withInitial { 0 }

/** Runs on OS event callbacks. No executor, timer, or dedicated JVM thread is created. */
private class JvmFileChangeListener(
    file: Path,
    private val listener: InternalFileChangeListener,
) : FileChangeListenerHandle {
    override val released = AtomicBoolean(false)
    private val path = Paths.get(file.toString())
    private val monitor = Any()
    private val nativeReleased = CountDownLatch(1)
    private val unixAttributes = "unix" in FileSystems.getDefault().supportedFileAttributeViews()
    private var pointer = 0L // Protected by monitor; detached before asynchronous native destruction.
    private var closing = false
    private var baseline: Metadata? = null

    private data class Metadata(
        val identity: Any?, val size: Long, val modified: FileTime,
        val created: FileTime, val changed: FileTime?,
    )

    private fun metadata(): Metadata? = try {
        val options = arrayOf(LinkOption.NOFOLLOW_LINKS)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, *options)
        if (!attributes.isRegularFile) throw IOException("Listener target is no longer a regular file: $path")
        val changed = if (unixAttributes) Files.getAttribute(path, "unix:ctime", *options) as FileTime else null
        Metadata(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(), changed)
    } catch (_: NoSuchFileException) { null }

    fun start() {
        try {
            synchronized(monitor) {
                baseline = metadata() ?: throw IOException("Listener file does not exist: $path")
                pointer = FileChangeListenerNative.create0(path.toString(), this)
                if (pointer == 0L) throw IOException("Unable to create file listener: $path")
                // Native creation is inert. Callbacks cannot access an uninstalled pointer.
                FileChangeListenerNative.start0(pointer)
            }
            // One reconciliation covers the registration gap; there is no periodic polling.
            if (!nativeChanged()) synchronized(monitor) {
                if (!closing && pointer != 0L) FileChangeListenerNative.close0(pointer, true)
            }
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    /** false asks native to cancel observation and finish with a termination callback. */
    @Suppress("unused")
    fun nativeChanged(): Boolean = synchronized(monitor) {
        if (closing || released.load()) return@synchronized false
        val previousDepth = fileListenerCallbackDepth.get()
        fileListenerCallbackDepth.set(previousDepth + 1)
        try {
            val next = metadata() ?: run {
                released.store(true)
                return@synchronized false
            }
            val changed = baseline != next
            baseline = next
            if (changed && !released.load()) runCatching { listener.onFileContentChanged() }
            !released.load()
        } catch (_: Throwable) {
            released.store(true)
            false
        } finally { fileListenerCallbackDepth.set(previousDepth) }
    }

    /** Internal stop signal; native still owns the object until cancellation finishes. */
    @Suppress("unused")
    fun nativeStopping() {
        synchronized(monitor) {
            closing = true
            pointer = 0L
            released.store(true)
        }
    }

    /** Native calls this only after deleting the OS object and its JNI global reference. */
    @Suppress("unused")
    fun nativeReleased(terminated: Boolean) {
        synchronized(monitor) {
            closing = true
            pointer = 0L
            released.store(true)
            nativeReleased.countDown()
            if (terminated) {
                val previousDepth = fileListenerCallbackDepth.get()
                fileListenerCallbackDepth.set(previousDepth + 1)
                try { runCatching { listener.onListenerTerminated() } }
                finally { fileListenerCallbackDepth.set(previousDepth) }
            }
        }
    }

    override fun release() {
        val inCallback = synchronized(monitor) {
            released.store(true)
            if (!closing) {
                closing = true
                val value = pointer
                pointer = 0L
                // Nonblocking native cancellation. Keeping monitor prevents a stale-pointer race
                // with nativeStopping; the actual disposal callback never runs inline.
                if (value != 0L) FileChangeListenerNative.close0(value, false)
                else nativeReleased.countDown()
            }
            fileListenerCallbackDepth.get() > 0
        }
        // An OS callback can close any listener, but must not block a shared event service.
        if (!inCallback) awaitRelease()
    }

    private fun awaitRelease() {
        var interrupted = false
        try {
            while (true) {
                try { nativeReleased.await(); return }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}

internal object FileChangeListenerNative {
    external fun create0(file: String, callback: Any): Long
    external fun start0(pointer: Long)
    external fun close0(pointer: Long, terminated: Boolean)
}
