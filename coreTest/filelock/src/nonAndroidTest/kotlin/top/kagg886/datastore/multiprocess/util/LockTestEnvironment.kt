package top.kagg886.datastore.multiprocess.util

import okio.Path

internal expect fun lockTestDirectory(): Path
internal expect fun startLockHelper(arguments: List<String>): LockHelperProcess
internal expect fun closeConcurrently(lock: FileLock)

internal class LockEventTimeout(message: String) : AssertionError(message)

internal interface LockHelperProcess : AutoCloseable {
    fun awaitEvent(name: String, timeoutMillis: Long = 10_000): LockEvent
    fun stop()
    fun closeStdin()
    fun awaitExit(timeoutMillis: Long = 10_000): Int
    fun terminate()
}

internal data class LockEvent(val name: String, val epochMillis: Long, val elapsedNanos: Long) {
    companion object {
        fun parse(line: String): LockEvent? {
            val match = Regex("""\{"event":"([A-Z]+)","pid":\d+,"epoch_ms":(\d+),"elapsed_ns":(\d+)\}""").matchEntire(line)
                ?: return null
            return LockEvent(match.groupValues[1], match.groupValues[2].toLong(), match.groupValues[3].toLong())
        }
    }
}
