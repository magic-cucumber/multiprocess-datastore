package top.kagg886.datastore.multiprocess.util

import top.kagg886.datastore.multiprocess.util.NativeLogger.Companion.log
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.DEBUG

import kotlin.time.Duration
import kotlin.concurrent.atomics.AtomicBoolean
import okio.Path

internal actual fun platformLock(path: Path, shared: Boolean, wait: Duration): FileLock? =
    FileLockImpl.create(path.toString(), shared, wait)

internal actual fun platformTryLock(path: Path, shared: Boolean): FileLock? =
    FileLockImpl.tryCreate(path.toString(), shared)

internal class FileLockImpl(private val ptr: Long) : FileLock {
    override val released: AtomicBoolean = AtomicBoolean(false)
    override fun release() {
        log(DEBUG, "FileLock.jvm", "Release requested ptr=${ptr.toString(16)}")
        release0(ptr)
    }

    private external fun release0(ptr: Long)

    companion object {
        init {
            NativeLibrary.ensureNativeLibraryInited()
        }

        fun create(path: String, shared: Boolean, wait: Duration): FileLockImpl? {
            log(DEBUG, "FileLock.jvm", "Request path=$path shared=$shared mode=wait timeout=$wait")
            return when (val ptr = create0(path, shared, wait.inWholeMilliseconds)) {
                0L -> {
                    log(DEBUG, "FileLock.jvm", "Native result path=$path mode=create -> null")
                    null
                }
                in 1L..Long.MAX_VALUE -> {
                    log(DEBUG, "FileLock.jvm", "Install wrapper path=$path ptr=${ptr.toString(16)}")
                    FileLockImpl(ptr)
                }
                else -> {
                    log(DEBUG, "FileLock.jvm", "Translate native result path=$path result=$ptr -> FileLockFailedException")
                    throw FileLockFailedException((-ptr).toInt())
                }
            }
        }

        fun tryCreate(path: String, shared: Boolean): FileLockImpl? {
            log(DEBUG, "FileLock.jvm", "Request path=$path shared=$shared mode=try")
            return when (val ptr = tryCreate0(path, shared)) {
                0L -> {
                    log(DEBUG, "FileLock.jvm", "Native result path=$path mode=tryCreate -> null")
                    null
                }
                in 1L..Long.MAX_VALUE -> {
                    log(DEBUG, "FileLock.jvm", "Install wrapper path=$path ptr=${ptr.toString(16)}")
                    FileLockImpl(ptr)
                }
                else -> {
                    log(DEBUG, "FileLock.jvm", "Translate native result path=$path result=$ptr -> FileLockFailedException")
                    throw FileLockFailedException((-ptr).toInt())
                }
            }
        }

        @JvmStatic
        @Throws(IllegalArgumentException::class, FileLockFailedException::class)
        private external fun create0(path: String, shared: Boolean, waitMills: Long): Long

        @JvmStatic
        @Throws(IllegalArgumentException::class, FileLockFailedException::class)
        private external fun tryCreate0(path: String, shared: Boolean): Long
    }
}
