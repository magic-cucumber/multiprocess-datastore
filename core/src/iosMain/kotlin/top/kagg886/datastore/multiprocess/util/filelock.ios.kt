@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.datastore.multiprocess.util

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.TimeSource
import okio.Path
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_SH
import platform.posix.O_CLOEXEC
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.flock
import platform.posix.open
import platform.posix.usleep

internal actual fun platformLock(path: Path, shared: Boolean, wait: Duration): FileLock? {
    val timeout = wait.inWholeMilliseconds
    require(timeout > 0) { "waitMills must be greater than 0" }

    val file = open(path.toString(), O_RDWR or O_CLOEXEC)
    if (file < 0) return null
    var acquired = false
    try {
        val started = TimeSource.Monotonic.markNow()
        var interval = 1L
        while (true) {
            var elapsed = started.elapsedNow().inWholeMilliseconds
            if (elapsed >= timeout) break
            if (acquire(file, shared)) {
                acquired = true
                break
            }
            elapsed = started.elapsedNow().inWholeMilliseconds
            if (elapsed >= timeout) break
            usleep((minOf(interval, timeout - elapsed) * 1_000).toUInt())
            interval = minOf(interval * 2, 64L)
        }
        // nonblocking attempt after timeout.
        if (!acquired) acquired = acquire(file, shared)
        return if (acquired) FileLockImpl(file) else null
    } finally {
        if (!acquired) close(file)
    }
}

internal actual fun platformTryLock(path: Path, shared: Boolean): FileLock? {
    val file = open(path.toString(), O_RDWR or O_CLOEXEC)
    if (file < 0) return null
    if (acquire(file, shared)) return FileLockImpl(file)
    close(file)
    return null
}

private fun acquire(file: Int, shared: Boolean): Boolean =
    flock(file, (if (shared) LOCK_SH else LOCK_EX) or LOCK_NB) == 0

private class FileLockImpl(private val file: Int) : FileLock {
    override val released: AtomicBoolean = AtomicBoolean(false)

    override fun release() {
        close(file)
    }
}
