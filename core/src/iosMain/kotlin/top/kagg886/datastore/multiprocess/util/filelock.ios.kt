@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package top.kagg886.datastore.multiprocess.util

import top.kagg886.datastore.multiprocess.util.NativeLogger.Companion.log
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.VERBOSE
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.DEBUG
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.WARN
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.ERROR

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import okio.Path
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_SH
import platform.posix.O_CLOEXEC
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.errno
import platform.posix.flock
import platform.posix.open
import platform.posix.usleep

internal actual fun platformLock(path: Path, shared: Boolean, wait: Duration): FileLock? {
    val timeout = wait.inWholeMilliseconds
    log(DEBUG, "FileLock.ios", "Request path=$path shared=$shared mode=wait timeout_ms=$timeout")
    require(timeout > 0) {
        log(ERROR, "FileLock.ios", "Invalid timeout path=$path timeout_ms=$timeout")
        "waitMills must be greater than 0"
    }

    val file = open(path.toString(), O_RDWR or O_CLOEXEC)
    if (file < 0) {
        val error = errno
        throw lockFailure("open path=$path", file, error)
    }
    log(DEBUG, "FileLock.ios", "Opened path=$path fd=$file")
    var acquired = false
    try {
        val started = TimeSource.Monotonic.markNow()
        var interval = 1L
        var finalAttempt = false
        var attempts = 0
        while (true) {
            var elapsed = started.elapsedNow()
            if (elapsed >= timeout.milliseconds) break
            attempts++
            if (acquire(file, shared)) {
                acquired = true
                break
            }
            if (finalAttempt) break
            elapsed = started.elapsedNow()
            if (elapsed >= timeout.milliseconds) break
            val remaining = timeout.milliseconds - elapsed
            finalAttempt = interval.milliseconds >= remaining
            // Leave one millisecond for the final attempt before the timeout.
            val delay = if (finalAttempt) {
                (remaining - 1.milliseconds).coerceAtLeast(Duration.ZERO)
            } else {
                interval.milliseconds
            }
            log(VERBOSE, "FileLock.ios", "Retry fd=$file attempt=$attempts delay=$delay remaining=$remaining final=$finalAttempt")
            usleep(delay.inWholeMicroseconds.toUInt())
            interval = minOf(interval * 2, 64L)
        }
        if (!acquired) log(WARN, "FileLock.ios", "Wait exhausted path=$path fd=$file attempts=$attempts elapsed=${started.elapsedNow()} timeout_ms=$timeout")
        return if (acquired) FileLockImpl(file) else null
    } finally {
        if (!acquired) closeLock(file, "rollback")
    }
}

internal actual fun platformTryLock(path: Path, shared: Boolean): FileLock? {
    log(DEBUG, "FileLock.ios", "Request path=$path shared=$shared mode=try")
    val file = open(path.toString(), O_RDWR or O_CLOEXEC)
    if (file < 0) {
        val error = errno
        throw lockFailure("open path=$path", file, error)
    }
    log(DEBUG, "FileLock.ios", "Opened path=$path fd=$file")
    var acquired = false
    try {
        acquired = acquire(file, shared)
        return if (acquired) FileLockImpl(file) else null
    } finally {
        if (!acquired) closeLock(file, "rollback")
    }
}

private fun acquire(file: Int, shared: Boolean): Boolean {
    if (flock(file, (if (shared) LOCK_SH else LOCK_EX) or LOCK_NB) == 0) {
        log(DEBUG, "FileLock.ios", "Acquired fd=$file shared=$shared")
        return true
    }
    val error = errno
    if (error == EWOULDBLOCK || error == EAGAIN) {
        log(VERBOSE, "FileLock.ios", "Contention fd=$file shared=$shared")
        return false
    }
    throw lockFailure("flock", file, error)
}

private class FileLockImpl(private val file: Int) : FileLock {
    override val released: AtomicBoolean = AtomicBoolean(false)

    override fun release() {
        closeLock(file, "release")
    }
}

private fun lockFailure(operation: String, file: Int, error: Int): FileLockFailedException {
    log(ERROR, "FileLock.ios", "Operation=$operation fd=$file failed errno=$error")
    return FileLockFailedException(error)
}

private fun closeLock(file: Int, reason: String) {
    if (close(file) != 0) {
        val error = errno
        log(ERROR, "FileLock.ios", "Close fd=$file reason=$reason failed errno=$error")
    } else log(DEBUG, "FileLock.ios", "Closed fd=$file reason=$reason")
}
