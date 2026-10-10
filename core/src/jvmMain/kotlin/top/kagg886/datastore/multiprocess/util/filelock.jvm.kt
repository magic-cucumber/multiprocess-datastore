package top.kagg886.datastore.multiprocess.util

import kotlin.time.Duration
import kotlin.concurrent.atomics.AtomicBoolean
import okio.Path

internal actual fun platformLock(path: Path, shared: Boolean, wait: Duration): FileLock? = FileLockImpl.create(path.toString(), shared, wait)
internal actual fun platformTryLock(path: Path, shared: Boolean): FileLock? = FileLockImpl.tryCreate(path.toString(), shared)
internal class FileLockImpl(private val ptr: Long) : FileLock {
    private val released = AtomicBoolean(false)
    override fun release() {
        if (released.compareAndSet(false, true)) release0(ptr)
    }
    private external fun release0(ptr: Long)

    companion object {
        init {
            NativeLibrary.ensureNativeLibraryInited()
        }

        fun create(path: String, shared: Boolean, wait: Duration): FileLockImpl? =
            create0(path, shared, wait.inWholeMilliseconds)
                .takeIf { it > 0 }
                ?.let { FileLockImpl(it) }

        fun tryCreate(path: String, shared: Boolean): FileLockImpl? =
            tryCreate0(path, shared)
                .takeIf { it > 0 }
                ?.let { FileLockImpl(it) }

        @JvmStatic
        private external fun create0(path: String, shared: Boolean, waitMills: Long): Long
        @JvmStatic
        private external fun tryCreate0(path: String, shared: Boolean): Long
    }
}
