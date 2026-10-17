@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.datastore.multiprocess.util

import top.kagg886.datastore.multiprocess.util.NativeLogger.Companion.log
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.VERBOSE
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.DEBUG
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.INFO
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.ERROR

import okio.Path
import kotlinx.cinterop.COpaquePointer
import kotlin.concurrent.atomics.AtomicBoolean
import platform.Foundation.NSLock
import platform.posix.*
import top.kagg886.datastore.multiprocess.counteratomic.shared_counter_increment
import top.kagg886.datastore.multiprocess.counteratomic.shared_counter_load

internal actual fun platformNewSharedCounter(path: Path): SharedCounter {
    log(DEBUG, "SharedCounter.ios", "Create requested path=$path")
    val file = open(path.toString(), O_RDWR or O_CREAT or O_CLOEXEC, 0x180 /* 0600 */)
    if (file < 0) {
        val error = errno
        throw counterFailure("open path=$path", file, error)
    }
    log(DEBUG, "SharedCounter.ios", "Opened path=$path fd=$file")
    var address: COpaquePointer? = MAP_FAILED
    var transferred = false
    try {
        if (ftruncate(file, 4) != 0) throw counterFailure("ftruncate", file, errno)
        log(DEBUG, "SharedCounter.ios", "File resized fd=$file bytes=4")
        address = mmap(null, 4u, PROT_READ or PROT_WRITE, MAP_SHARED, file, 0)
        if (address == MAP_FAILED) throw counterFailure("mmap", file, errno)
        log(DEBUG, "SharedCounter.ios", "Mapped fd=$file address=$address bytes=4")
        return SharedCounterImpl(file, address).also {
            transferred = true
            log(INFO, "SharedCounter.ios", "Created path=$path fd=$file address=$address")
        }
    } finally {
        if (!transferred) {
            log(DEBUG, "SharedCounter.ios", "Rollback fd=$file address=$address")
            if (address != MAP_FAILED && munmap(address, 4u) != 0) counterFailure("rollback.munmap", file, errno)
            if (close(file) != 0) counterFailure("rollback.close", file, errno)
        }
    }
}

private class SharedCounterImpl(private val file: Int, private val address: COpaquePointer?) : SharedCounter {
    override val closed: AtomicBoolean = AtomicBoolean(false)
    private val lock = NSLock()

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    override fun getValue(): Int = withLock {
        if (closed.load()) throw counterFailure("access closed counter", file, EBADF)
        shared_counter_load(address).toInt().also {
            log(VERBOSE, "SharedCounter.ios", "Read fd=$file value=$it")
        }
    }

    override fun incrementAndGetValue(): Int = withLock {
        if (closed.load()) throw counterFailure("access closed counter", file, EBADF)
        shared_counter_increment(address).toInt().also {
            log(VERBOSE, "SharedCounter.ios", "Increment fd=$file value=$it")
        }
    }

    override fun close0(): Unit = withLock {
        log(DEBUG, "SharedCounter.ios", "Close requested fd=$file address=$address")
        var error = 0
        if (munmap(address, 4u) != 0) {
            error = errno
            counterFailure("munmap", file, error)
        } else log(DEBUG, "SharedCounter.ios", "Unmapped fd=$file address=$address")
        if (close(file) != 0) {
            val failure = errno
            counterFailure("close", file, failure)
            if (error == 0) error = failure
        } else log(DEBUG, "SharedCounter.ios", "File closed fd=$file")
        if (error != 0) throw SharedCounterException(error)
    }
}

private fun counterFailure(operation: String, file: Int, error: Int): SharedCounterException {
    log(ERROR, "SharedCounter.ios", "Operation=$operation fd=$file failed errno=$error")
    return SharedCounterException(error)
}
