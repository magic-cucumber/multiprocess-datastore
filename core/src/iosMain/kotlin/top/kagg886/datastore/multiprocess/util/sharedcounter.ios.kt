@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.datastore.multiprocess.util

import okio.Path
import kotlinx.cinterop.COpaquePointer
import kotlin.concurrent.atomics.AtomicBoolean
import platform.Foundation.NSLock
import platform.posix.*
import top.kagg886.datastore.multiprocess.counteratomic.shared_counter_increment
import top.kagg886.datastore.multiprocess.counteratomic.shared_counter_load

internal actual fun platformNewSharedCounter(path: Path): SharedCounter {
    val file = open(path.toString(), O_RDWR or O_CREAT or O_CLOEXEC, 0x180 /* 0600 */)
    if (file < 0) throw SharedCounterException(errno)
    var address: COpaquePointer? = MAP_FAILED
    var transferred = false
    try {
        if (ftruncate(file, 4) != 0) throw SharedCounterException(errno)
        address = mmap(null, 4u, PROT_READ or PROT_WRITE, MAP_SHARED, file, 0)
        if (address == MAP_FAILED) throw SharedCounterException(errno)
        return SharedCounterImpl(file, address).also { transferred = true }
    } finally {
        if (!transferred) {
            if (address != MAP_FAILED) munmap(address, 4u)
            close(file)
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
        if (closed.load()) throw SharedCounterException(EBADF)
        shared_counter_load(address).toInt()
    }

    override fun incrementAndGetValue(): Int = withLock {
        if (closed.load()) throw SharedCounterException(EBADF)
        shared_counter_increment(address).toInt()
    }

    override fun close0(): Unit = withLock {
        var error = if (munmap(address, 4u) == 0) 0 else errno
        if (close(file) != 0 && error == 0) error = errno
        if (error != 0) throw SharedCounterException(error)
    }
}
