package top.kagg886.datastore.multiprocess.util

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okio.Path
import okio.Path.Companion.toOkioPath

internal actual fun counterTestDirectory(): Path = Files.createTempDirectory("shared-counter-test-").toOkioPath()
internal actual fun counterEpochMillis(): Long = System.currentTimeMillis()
internal actual fun counterWaitUntil(epochMillis: Long) {
    val target = System.nanoTime() + (epochMillis - System.currentTimeMillis()) * 1_000_000
    val sleepNanos = target - System.nanoTime() - 2_000_000
    if (sleepNanos > 0) TimeUnit.NANOSECONDS.sleep(sleepNanos)
    while (System.nanoTime() < target) Thread.onSpinWait()
}

internal actual fun counterConcurrently(actions: List<() -> Unit>) {
    val ready = CountDownLatch(actions.size)
    val start = CountDownLatch(1)
    val failure = AtomicReference<Throwable>()
    val threads = actions.mapIndexed { index, action ->
        thread(isDaemon = true, name = "counter-test-$index") {
            ready.countDown()
            try {
                check(start.await(10, TimeUnit.SECONDS)) { "Concurrent start timed out" }
                action()
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
    }
    try { check(ready.await(10, TimeUnit.SECONDS)) { "Workers did not become ready" } }
    finally { start.countDown() }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    threads.forEach {
        val remaining = deadline - System.nanoTime()
        if (remaining > 0) it.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
    }
    if (threads.any { it.isAlive }) {
        threads.forEach { it.interrupt() }
        error("Concurrent counter operations did not finish")
    }
    failure.get()?.let { throw it }
}

internal actual fun startCounterHelper(arguments: List<String>): CounterHelperProcess {
    val executable = checkNotNull(System.getenv("SHARED_COUNTER_HELPER")) { "SHARED_COUNTER_HELPER is not configured" }
    return JvmCounterHelper(ProcessBuilder(listOf(executable) + arguments).start())
}

private class JvmCounterHelper(private val process: Process) : CounterHelperProcess {
    private val lines = LinkedBlockingQueue<String>()
    private val transcript = ArrayDeque<String>()
    private val stderr = StringBuilder()
    private val readerFailure = AtomicReference<Throwable>()
    private val outputReader = thread(isDaemon = true, name = "counter-helper-stdout") {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                input.forEachLine { if (!dispatchHelperLog(it)) lines.put(it) }
            }
        } catch (error: Throwable) { readerFailure.compareAndSet(null, error) }
        finally { lines.put(EOF) }
    }
    private val errorReader = thread(isDaemon = true, name = "counter-helper-stderr") {
        try {
            process.errorStream.bufferedReader(Charsets.UTF_8).use { input ->
                input.forEachLine { line -> synchronized(stderr) {
                    if (stderr.length < 16_384) stderr.appendLine(line)
                } }
            }
        } catch (error: Throwable) { readerFailure.compareAndSet(null, error) }
    }
    private val input = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private var stdinClosed = false
    private var closed = false

    override fun send(command: String) { input.write(command); input.newLine(); input.flush() }
    override fun nextLine(timeoutMillis: Long): String {
        val line = lines.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        check(line != null && line != EOF) {
            "Helper EOF/timeout; stdout=$transcript; stderr=${synchronized(stderr) { stderr.toString() }}; reader=${readerFailure.get()}"
        }
        if (transcript.size == 32) transcript.removeFirst()
        transcript.addLast(line)
        return line
    }
    override fun closeStdin() { if (!stdinClosed) { stdinClosed = true; input.close() } }
    override fun awaitExit(timeoutMillis: Long): Int {
        val started = System.nanoTime()
        check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "Helper did not exit: $transcript" }
        val remaining = timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        if (remaining > 0) outputReader.join(remaining)
        check(!outputReader.isAlive) { "Helper output reader did not finish after exit" }
        val code = process.exitValue()
        NativeLogger.log(NativeLogger.Level.DEBUG, "CounterHelperHost", "time_us=${testEpochMicros()} Exit observed pid=${process.pid()} code=$code")
        return code
    }
    override fun terminate() {
        if (process.isAlive) {
            NativeLogger.log(NativeLogger.Level.WARN, "CounterHelperHost", "time_us=${testEpochMicros()} Force terminate pid=${process.pid()}")
            process.destroyForcibly()
        }
    }
    override fun close() {
        if (closed) return
        try {
            terminate()
            awaitExit(5000)
        } finally {
            try { closeStdin() } finally {
                outputReader.join(5000)
                errorReader.join(5000)
                closed = true
                check(!outputReader.isAlive && !errorReader.isAlive) { "Helper readers did not finish" }
            }
        }
    }
    companion object { const val EOF = "<EOF>" }
}
