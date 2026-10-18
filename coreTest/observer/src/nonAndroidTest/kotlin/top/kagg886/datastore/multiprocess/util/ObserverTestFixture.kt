package top.kagg886.datastore.multiprocess.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.*
import kotlin.time.TimeSource
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import top.kagg886.datastore.multiprocess.util.NativeLogger.Companion.log
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.*
import kotlin.time.Duration.Companion.milliseconds

/** All listeners and writers use real files; no replacement factory or synthetic callbacks. */
abstract class ObserverTestFixture : NativeLoggingTestFixture() {
    internal val fs = FileSystem.SYSTEM
    internal lateinit var directory: Path
    internal lateinit var file: Path
    internal lateinit var other: Path
    private val handles = AtomicReference<List<AutoCloseable>>(emptyList())
    private val helpers = mutableListOf<ObserverHelperProcess>()

    @BeforeTest fun setup() {
        directory = observerTestDirectory()
        file = directory / "监听 target 文件.bin"
        other = directory / "其他 file.bin"
        seed(file, "initial")
        seed(other, "other")
    }

    @AfterTest fun cleanup() {
        val failures = mutableListOf<Throwable>()
        handles.load().forEach { runCatching { it.close() }.exceptionOrNull()?.let(failures::add) }
        helpers.forEach { runCatching { it.close() }.exceptionOrNull()?.let(failures::add) }
        runCatching { fs.deleteRecursively(directory) }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    internal fun seed(path: Path, text: String) { fs.write(path) { writeUtf8(text) } }
    internal fun text(path: Path = file): String = fs.read(path) { readUtf8() }
    internal fun probe(path: Path = file, changed: () -> Unit = {}, terminated: () -> Unit = {}): ObserverProbe =
        ObserverProbe(path, changed, terminated)
    internal fun observe(probe: ObserverProbe, path: Path = probe.path): AutoCloseable =
        FileChangeListener.observe(path, probe).also { handle ->
            while (true) {
                val before = handles.load()
                if (handles.compareAndSet(before, before + handle)) break
            }
        }
    internal fun rawHelper(arguments: List<String>): ObserverHelperProcess =
        startObserverHelper(arguments).also { helpers += it }
    internal fun helper(path: Path = file, sibling: Path = other): ObserverSession {
        val process = rawHelper(listOf("--path", path.toString(), "--other", sibling.toString()))
        assertEquals(1L, process.event("READY").number("protocol"))
        return ObserverSession(process)
    }
    internal fun await(message: String, condition: () -> Boolean): Unit = await({ message }, condition)
    private fun await(message: () -> String, condition: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (start.elapsedNow().inWholeMilliseconds >= 10_000) {
                val detail = "time_us=${testEpochMicros()} elapsed=${start.elapsedNow()} ${message()}"
                log(ERROR, "ObserverTest", detail)
                fail(detail)
            }
            observerPause(5)
        }
    }
    internal fun changed(probe: ObserverProbe, expected: String) {
        log(DEBUG, "ObserverTest", "time_us=${testEpochMicros()} Await changed path=${probe.path} expected=${expected.hex()} ${probe.describe()}")
        await({ "Missing changed for ${probe.path}: expected=$expected; ${probe.describe()}" }) {
            probe.changes.load() > 0 && probe.content.load() == expected
        }
        assertEquals(0, probe.terminations.load(), probe.describe())
        assertEquals(expected, text(probe.path))
    }
    internal fun terminated(probe: ObserverProbe) {
        await({ "Missing terminated for ${probe.path}: ${probe.describe()}" }) { probe.terminations.load() > 0 }
        assertEquals(1, probe.terminations.load(), probe.describe())
    }
    // A bounded absence check, never an assertion about all future time.
    internal fun unchanged(probe: ObserverProbe, changes: Int, terminations: Int = 0) {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow().inWholeMilliseconds < 300) {
            assertEquals(changes, probe.changes.load(), probe.describe())
            assertEquals(terminations, probe.terminations.load(), probe.describe())
            observerPause(5)
        }
        assertEquals(changes, probe.changes.load(), probe.describe())
        assertEquals(terminations, probe.terminations.load(), probe.describe())
    }
}

internal class ObserverProbe(
    val path: Path,
    private val onChanged: () -> Unit,
    private val onTerminated: () -> Unit,
) : FileChangeListener {
    val changes = AtomicInt(0)
    val terminations = AtomicInt(0)
    val content = AtomicReference<String?>(null)
    private val probeId = nextId.fetchAndAdd(1)
    private val attempts = AtomicInt(0)
    private val readFailures = AtomicInt(0)
    private val lastReadError = AtomicReference<String?>(null)
    override fun onFileContentChanged() {
        content.store(readContent())
        try { onChanged() } finally {
            val count = changes.fetchAndAdd(1) + 1
            log(VERBOSE, "ObserverProbe", "time_us=${testEpochMicros()} Callback finished probe=$probeId attempt=${attempts.load()} changes=$count")
        }
    }
    private fun readContent(): String {
        val previous = content.load()
        var retry = 0
        while (true) {
            //等待10ms防止文件被占用导致无法read
            runBlocking { delay(10.milliseconds) }
            val attempt = attempts.fetchAndAdd(1) + 1
            val started = TimeSource.Monotonic.markNow()
            log(VERBOSE, "ObserverProbe", "time_us=${testEpochMicros()} Read begin probe=$probeId attempt=$attempt path=$path")
            val value = try {
                FileSystem.SYSTEM.read(path) { readUtf8() }
            } catch (error: Throwable) {
                readFailures.fetchAndAdd(1)
                lastReadError.store(error.stackTraceToString())
                log(ERROR, "ObserverProbe", "time_us=${testEpochMicros()} Read failed probe=$probeId attempt=$attempt path=$path elapsed=${started.elapsedNow()} ${error.stackTraceToString()}")
                if (retry == 3) throw error
                null
            }
            if (value != null) {
                lastReadError.store(null)
                log(DEBUG, "ObserverProbe", "time_us=${testEpochMicros()} Read succeeded probe=$probeId attempt=$attempt path=$path elapsed=${started.elapsedNow()} sameContent=${previous == value} utf8_hex=${value.hex()}")
                if (value != previous || retry == 3) return value
            }
            retry++
            log(DEBUG, "ObserverProbe", "time_us=${testEpochMicros()} Retry read probe=$probeId path=$path retry=$retry delay_ms=10 reason=${if (value == null) "read-failed" else "unchanged"}")
            observerPause(10)
        }
    }
    override fun onListenerTerminated() {
        log(DEBUG, "ObserverProbe", "time_us=${testEpochMicros()} Terminated probe=$probeId path=$path")
        try { onTerminated() } finally { terminations.fetchAndAdd(1) }
    }
    fun describe(): String = "probe=$probeId, changes=${changes.load()}, terminations=${terminations.load()}, content=${content.load()}, attempts=${attempts.load()}, readFailures=${readFailures.load()}, lastReadError=${lastReadError.load()}"
    private companion object { val nextId = AtomicInt(1) }
}

internal class ObserverSession(val process: ObserverHelperProcess) {
    private var id = 0L
    fun command(command: String, event: String = "DONE"): ObserverEvent {
        process.send("${++id} $command")
        return process.event(event, id)
    }
    fun write(value: String, target: String = "TARGET") { command("WRITE $target ${value.hex()}") }
    fun close() { command("CLOSE", "CLOSED"); assertEquals(0, process.awaitExit()) }
}
internal fun String.hex(): String = if (isEmpty()) "-" else encodeToByteArray().joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}
