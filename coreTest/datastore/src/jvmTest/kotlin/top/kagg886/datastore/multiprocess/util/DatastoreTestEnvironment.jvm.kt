package top.kagg886.datastore.multiprocess.util

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okio.Path
import okio.Path.Companion.toOkioPath

internal actual fun datastoreTestDirectory(): Path = Files.createTempDirectory("datastore-test-").toOkioPath()
internal actual fun startDatastoreHelper(arguments: List<String>): DatastoreHelperProcess {
    val java = checkNotNull(System.getenv("DATASTORE_HELPER_JAVA"))
    val classpath = checkNotNull(System.getenv("DATASTORE_HELPER_CLASSPATH"))
    return JvmDatastoreHelper(ProcessBuilder(listOf(java, "-cp", classpath,
        "top.kagg886.datastore.multiprocess.testhelper.MainKt") + arguments).start())
}

private class JvmDatastoreHelper(private val process: Process) : DatastoreHelperProcess {
    private val lines = LinkedBlockingQueue<String>()
    private val transcript = ArrayDeque<String>()
    private val stderr = StringBuilder()
    private val readerFailure = AtomicReference<Throwable>()
    private val outputReader = thread(isDaemon = true, name = "datastore-helper-stdout") {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                input.forEachLine { line ->
                    if (line.startsWith("@DS\t")) lines.put(line)
                    else synchronized(stderr) { if (stderr.length < 16_384) stderr.appendLine(line) }
                }
            }
        } catch (error: Throwable) { readerFailure.compareAndSet(null, error) }
        finally { lines.put(EOF) }
    }
    private val errorReader = thread(isDaemon = true, name = "datastore-helper-stderr") {
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

    override fun send(command: String) {
        if (transcript.size == 32) transcript.removeFirst()
        transcript.addLast("> " + command.take(256))
        input.write(command); input.newLine(); input.flush()
    }
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
        return code
    }
    override fun terminate() {
        if (process.isAlive) {
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


internal actual fun datastoreVerboseLogging(): Boolean =
    (System.getenv("DATASTORE_TEST_LOG_LEVEL")?.toIntOrNull() ?: 3) <= 1
