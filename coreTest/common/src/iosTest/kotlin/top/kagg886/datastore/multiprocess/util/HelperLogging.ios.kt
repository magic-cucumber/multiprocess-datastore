@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.datastore.multiprocess.util

import kotlinx.cinterop.*
import okio.Buffer
import platform.posix.*

internal actual fun testEpochMicros(): Long = memScoped {
    val time = alloc<timeval>()
    check(gettimeofday(time.ptr, null) == 0)
    time.tv_sec * 1_000_000 + time.tv_usec
}

/** Nonblocking drain while reaping a child; preserve protocol lines for later consumers. */
internal fun drainHelperOutput(fd: Int, partial: Buffer, pending: ArrayDeque<String>): Unit = memScoped {
    val descriptor = alloc<pollfd>()
    descriptor.fd = fd
    descriptor.events = POLLIN.toShort()
    val byte = alloc<ByteVar>()
    while (true) {
        val result = poll(descriptor.ptr, 1u, 0)
        if (result < 0 && errno == EINTR) continue
        check(result >= 0) { "Helper drain poll failed: $errno" }
        if (result == 0) break
        val count = read(fd, byte.ptr, 1u)
        if (count < 0 && errno == EINTR) continue
        check(count >= 0) { "Helper drain read failed: $errno" }
        if (count == 0L) break
        if (byte.value.toInt() == 10) {
            val line = partial.readUtf8().removeSuffix("\r")
            if (!dispatchHelperLog(line)) pending.addLast(line)
        } else partial.writeByte(byte.value.toInt())
    }
}
