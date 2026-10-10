package top.kagg886.datastore.multiprocess.util

import kotlin.time.Duration
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/10/10 17:13
 * ================================================
 */

internal interface FileLock : AutoCloseable {
    val released: AtomicBoolean

    // don't call it directly
    fun release()

    override fun close() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) release()
    }

    companion object {
        //get a lock in `wait` milliseconds
        fun lock(path: Path, shared: Boolean, wait: Duration): FileLock? {
            require(FileSystem.SYSTEM.exists(path)) { "Lock file does not exist: $path" }
            return platformLock(path, shared, wait)
        }

        //attempt get a lock
        fun tryLock(path: Path, shared: Boolean): FileLock? {
            require(FileSystem.SYSTEM.exists(path)) { "Lock file does not exist: $path" }
            return platformTryLock(path, shared)
        }
    }
}

internal expect fun platformLock(path: Path, shared: Boolean, wait: Duration): FileLock?
internal expect fun platformTryLock(path: Path, shared: Boolean): FileLock?
