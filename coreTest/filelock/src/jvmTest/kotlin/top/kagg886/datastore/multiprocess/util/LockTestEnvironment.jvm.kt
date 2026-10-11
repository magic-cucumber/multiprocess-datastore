@file:OptIn(top.kagg886.datastore.multiprocess.util.VisibleForTest::class)

package top.kagg886.datastore.multiprocess.util

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okio.Path
import okio.Path.Companion.toOkioPath

internal actual fun lockTestDirectory(): Path = Files.createTempDirectory("file-lock-test-").toOkioPath()

internal actual fun closeConcurrently(lock: FileLock) {
    val ready = CountDownLatch(8)
    val start = CountDownLatch(1)
    val failure = AtomicReference<Throwable>()
    val threads = List(8) {
        thread {
            ready.countDown()
            start.await()
            try { lock.close() } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
    }
    ready.await()
    start.countDown()
    threads.forEach { it.join(5000); check(!it.isAlive) { "close() did not finish" } }
    failure.get()?.let { throw it }
}

internal actual fun startLockHelper(arguments: List<String>): LockHelperProcess {
    val executable = checkNotNull(System.getenv("FILE_LOCK_HELPER")) { "FILE_LOCK_HELPER is not configured" }
    return JvmLockHelper(ProcessBuilder(listOf(executable) + arguments).redirectErrorStream(true).start())
}

private class JvmLockHelper(private val process: Process) : LockHelperProcess {
    private val lines = LinkedBlockingQueue<String>()
    private val transcript = mutableListOf<String>()
    private val reader = thread(isDaemon = true, name = "lock-helper-output") {
        process.inputStream.bufferedReader().use { input ->
            input.forEachLine { lines.put(it) }
        }
        lines.put(EOF)
    }
    private val input = process.outputStream.bufferedWriter()

    override fun awaitEvent(name: String, timeoutMillis: Long): LockEvent {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val line = if (remaining > 0) lines.poll(remaining, TimeUnit.NANOSECONDS) else null
            if (line == null) throw LockEventTimeout("Waiting for $name: $transcript")
            check(line != EOF) { "Helper exited before $name: $transcript" }
            transcript += line
            val event = LockEvent.parse(line)
            if (event?.name == name) return event
        }
    }

    override fun stop() { input.write("STOP\n"); input.flush() }
    override fun closeStdin() { input.close() }
    override fun awaitExit(timeoutMillis: Long): Int {
        check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "Helper did not exit: $transcript" }
        return process.exitValue()
    }
    override fun terminate() { if (process.isAlive) process.destroyForcibly() }
    override fun close() {
        terminate()
        awaitExit(5000)
        input.close()
        reader.join(5000)
        check(!reader.isAlive) { "Helper output reader did not terminate" }
    }
    companion object { const val EOF = "<EOF>" }
}
