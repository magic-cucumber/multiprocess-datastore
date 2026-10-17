package top.kagg886.datastore.multiprocess.util

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.*
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM

class SharedCounterTest : NativeLoggingTestFixture() {
    private val fs = FileSystem.SYSTEM
    private lateinit var directory: Path
    private lateinit var file: Path
    private val counters = mutableListOf<SharedCounter>()
    private val helpers = mutableListOf<CounterHelperProcess>()

    @BeforeTest fun setup() {
        directory = counterTestDirectory()
        file = directory / "共享 counter 文件.bin"
    }
    @AfterTest fun cleanup() {
        val failures = mutableListOf<Throwable>()
        helpers.forEach { runCatching { it.close() }.exceptionOrNull()?.let(failures::add) }
        counters.forEach { runCatching { it.close() }.exceptionOrNull()?.let(failures::add) }
        runCatching { fs.deleteRecursively(directory) }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
    private fun counter(path: Path = file): SharedCounter = SharedCounter.new(path).also { counters += it }
    private fun helper(path: Path = file): CounterHelperProcess = rawHelper(listOf("--path", path.toString()))
        .also { assertEquals(1L, it.event("READY").number("protocol")) }
    private fun rawHelper(args: List<String>): CounterHelperProcess = startCounterHelper(args).also { helpers += it }
    private fun seed(value: Int, path: Path = file) { fs.write(path) { writeIntLe(value) } }
    private fun value(process: CounterHelperProcess, id: Long, command: String): Int {
        process.send("$id $command")
        return process.event("VALUE", id).number("value").toInt()
    }
    private fun close(process: CounterHelperProcess, id: Long) {
        process.send("$id CLOSE")
        process.event("CLOSED", id)
        assertEquals(0, process.awaitExit())
    }
    private fun results(process: CounterHelperProcess, id: Long, count: Int): Pair<List<Int>, CounterEvent> {
        process.event("STARTED", id).also { assertEquals(count.toLong(), it.number("count")) }
        val values = mutableListOf<Int>()
        while (values.size < count) {
            val event = process.event("VALUES", id)
            assertEquals(values.size.toLong(), event.number("offset"))
            val batch = event.values()
            assertTrue(batch.size in 1..256)
            values += batch
        }
        assertEquals(count, values.size)
        return values to process.event("DONE", id).also { assertEquals(count.toLong(), it.number("count")) }
    }
    private fun assertSequence(values: List<Int>, first: Int = 1) {
        assertEquals(List(values.size) { first + it }, values.sorted(), "Lost or duplicated increment return values")
    }

    @Test fun createsMissingFileAtZero() {
        val c = counter()
        assertEquals(0, c.getValue())
        assertEquals(4L, fs.metadata(file).size)
        assertFalse(c.closed.load())
    }
    @Test fun emptyFileStartsAtZero() {
        fs.write(file) { }
        assertEquals(0, counter().getValue())
    }
    @Test fun incrementReturnsUpdatedValueAndReadDoesNotMutate() {
        val c = counter()
        repeat(100) {
            assertEquals(it + 1, c.incrementAndGetValue())
            repeat(3) { _ -> assertEquals(it + 1, c.getValue()) }
        }
    }
    @Test fun existingValueSurvivesOpenAndReopen() {
        seed(42)
        val c = counter()
        assertEquals(42, c.getValue())
        assertEquals(43, c.incrementAndGetValue())
        c.close()
        assertEquals(43, counter().getValue())
    }
    @Test fun instancesShareUpdatesAndCloseIndependently() {
        val a = counter()
        val b = counter()
        assertEquals(1, a.incrementAndGetValue())
        assertEquals(1, b.getValue())
        assertEquals(2, b.incrementAndGetValue())
        assertEquals(2, a.getValue())
        a.close()
        assertEquals(3, b.incrementAndGetValue())
    }
    @Test fun differentFilesAreIndependent() {
        val a = counter()
        val b = counter(directory / "other.bin")
        a.incrementAndGetValue()
        assertEquals(0, b.getValue())
        b.incrementAndGetValue()
        assertEquals(1, a.getValue())
    }
    @Test fun helperAndKotlinSeeUpdatesWithoutReopening() {
        val c = counter()
        val h = helper()
        assertEquals(0, value(h, 1, "READ"))
        assertEquals(1, c.incrementAndGetValue())
        assertEquals(1, value(h, 2, "READ"))
        assertEquals(2, value(h, 3, "INC"))
        assertEquals(2, c.getValue())
        close(h, 4)
        assertEquals(3, c.incrementAndGetValue())
    }
    @Test fun helperZeroAndChunkedBatches() {
        val c = counter()
        val h = helper()
        h.send("1 RUN 0")
        assertTrue(results(h, 1, 0).first.isEmpty())
        h.send("2 RUN 1025")
        assertSequence(results(h, 2, 1025).first)
        assertEquals(1025, c.getValue())
        close(h, 3)
    }
    @Test fun timedSingleIncrementsCompeteAcrossProcesses() {
        val c = counter()
        val processes = List(4) { helper() }
        repeat(8) { round ->
            val id = round + 1L
            val target = counterEpochMillis() + 300
            processes.forEach { it.send("$id INC $target") }
            processes.forEach { assertEquals(target, it.event("ARMED", id).number("target_epoch_ms")) }
            assertTrue(counterEpochMillis() < target, "ARMED barrier missed target")
            counterWaitUntil(target)
            val values = mutableListOf(c.incrementAndGetValue())
            processes.forEach {
                val event = it.event("VALUE", id)
                assertTrue(event.number("late_ns") >= 0, event.raw)
                assertTrue(event.number("started_epoch_ms") >= target - 2, event.raw)
                values += event.number("value").toInt()
            }
            assertSequence(values, round * 5 + 1)
            assertEquals((round + 1) * 5, c.getValue())
        }
        processes.forEach { close(it, 9) }
    }
    @Test fun timedBatchesDoNotLoseOrDuplicateIncrements() {
        val c = counter()
        val processes = List(4) { helper() }
        val count = 20_000
        repeat(3) { round ->
            val id = round + 1L
            val target = counterEpochMillis() + 300
            processes.forEach { it.send("$id RUN $count $target") }
            processes.forEach { it.event("ARMED", id) }
            assertTrue(counterEpochMillis() < target, "ARMED barrier missed target")
            counterWaitUntil(target)
            val values = MutableList(count) { c.incrementAndGetValue() }
            processes.forEach { process ->
                val (external, done) = results(process, id, count)
                assertTrue(done.number("late_ns") >= 0, done.raw)
                values += external
            }
            assertSequence(values, round * count * 5 + 1)
            assertEquals((round + 1) * count * 5, c.getValue())
        }
        processes.forEach { close(it, 4) }
    }
    @Test fun pastTimestampRunsImmediatelyAndReportsLateness() {
        counter()
        val h = helper()
        val target = counterEpochMillis() - 1000
        h.send("1 INC $target")
        h.event("ARMED", 1)
        val event = h.event("VALUE", 1)
        assertEquals(1L, event.number("value"))
        assertTrue(event.number("late_ns") >= 1_000_000_000, event.raw)
        close(h, 2)
    }
    private fun concurrentIncrement(sharedInstance: Boolean) {
        val instances = List(8) { counter() }
        val values = List(8) { IntArray(5000) }
        counterConcurrently(List(8) { worker -> {
            val c = if (sharedInstance) instances[0] else instances[worker]
            repeat(5000) { values[worker][it] = c.incrementAndGetValue() }
        } })
        assertSequence(values.flatMap { it.toList() })
        assertEquals(40_000, instances[0].getValue())
    }
    @Test fun threadsSharingInstanceIncrementAtomically() = concurrentIncrement(true)
    @Test fun threadsUsingIndependentMappingsIncrementAtomically() = concurrentIncrement(false)
    @Test fun concurrentFirstOpenDoesNotResetIncrements() {
        val instances = arrayOfNulls<SharedCounter>(8)
        val values = List(8) { IntArray(1000) }
        try {
            counterConcurrently(List(8) { worker -> {
                val c = SharedCounter.new(file)
                instances[worker] = c
                repeat(1000) { values[worker][it] = c.incrementAndGetValue() }
            } })
            assertSequence(values.flatMap { it.toList() })
            assertEquals(8000, instances[0]!!.getValue())
        } finally { instances.forEach { it?.close() } }
    }
    @Test fun signedCounterWrapsAndInteroperatesWithHelper() {
        seed(Int.MAX_VALUE)
        val c = counter()
        val h = helper()
        assertEquals(Int.MAX_VALUE, value(h, 1, "READ"))
        assertEquals(Int.MIN_VALUE, c.incrementAndGetValue())
        assertEquals(Int.MIN_VALUE, value(h, 2, "READ"))
        assertEquals(Int.MIN_VALUE + 1, value(h, 3, "INC"))
        assertEquals(Int.MIN_VALUE + 1, c.getValue())
        close(h, 4)
    }
    @Test fun minusOneWrapsToZero() {
        seed(-1)
        assertEquals(0, counter().incrementAndGetValue())
    }
    @Test fun repeatedCloseDoesNotAffectNewInstance() {
        val old = counter()
        old.incrementAndGetValue()
        old.close()
        assertTrue(old.closed.load())
        val next = counter()
        repeat(20) { old.close() }
        assertEquals(2, next.incrementAndGetValue())
        assertFailsWith<IOException> { old.getValue() }
        assertFailsWith<IOException> { old.incrementAndGetValue() }
    }
    @Test fun concurrentCloseReleasesOnce() {
        val releases = AtomicInt(0)
        val fake = object : SharedCounter {
            override val closed = AtomicBoolean(false)
            override fun getValue(): Int = error("Unused")
            override fun incrementAndGetValue(): Int = error("Unused")
            override fun close0() { releases.fetchAndAdd(1) }
        }
        counterConcurrently(List(8) { { fake.close() } })
        assertEquals(1, releases.load())
        val real = counter()
        counterConcurrently(List(8) { { real.close() } })
        assertTrue(real.closed.load())
        assertEquals(1, counter().incrementAndGetValue())
    }
    @Test fun operationsRacingWithCloseCompleteOrThrowClosedException() {
        val c = counter()
        val values = List(4) { mutableListOf<Int>() }
        counterConcurrently(List(4) { worker -> {
            repeat(2000) {
                try { values[worker] += c.incrementAndGetValue(); c.getValue() }
                catch (_: IOException) { return@repeat }
            }
        } } + listOf({ c.close() }))
        assertTrue(c.closed.load())
        assertSequence(values.flatten())
        assertEquals(values.sumOf { it.size }, counter().getValue())
        assertFailsWith<IOException> { c.getValue() }
    }
    @Test fun invalidPathsFailAndDoNotBreakSubsequentCreation() {
        val parentFile = directory / "plain-file"
        fs.write(parentFile) { writeByte(1) }
        for (path in listOf(directory, directory / "missing-parent" / "counter", parentFile / "counter")) {
            assertFailsWith<IOException> { SharedCounter.new(path) }
        }
        assertEquals(0, counter().getValue())
    }
    @Test fun repeatedOpenIncrementClosePreservesValue() {
        repeat(100) { index ->
            val c = counter()
            assertEquals(index, c.getValue())
            assertEquals(index + 1, c.incrementAndGetValue())
            c.close()
        }
        assertEquals(100, counter().getValue())
    }
    @Test fun helperEofReleasesMappingAndPreservesUpdates() {
        val c = counter()
        val h = helper()
        value(h, 1, "INC")
        h.closeStdin()
        h.event("CLOSED")
        assertEquals(0, h.awaitExit())
        assertEquals(2, c.incrementAndGetValue())
    }
    @Test fun terminatingHelperPreservesAcknowledgedUpdates() {
        val c = counter()
        val h = helper()
        assertEquals(1, value(h, 1, "INC"))
        h.terminate()
        assertNotEquals(0, h.awaitExit())
        assertEquals(2, c.incrementAndGetValue())
        assertEquals(2, counter().getValue())
    }
    @Test fun helperRejectsInvalidArgumentsAndFiles() {
        seed(42)
        for (args in listOf(emptyList(), listOf("--unknown", "x"), listOf("--path", file.toString(), "--path", file.toString()))) {
            val h = rawHelper(args)
            h.event("ERROR")
            assertEquals(2, h.awaitExit())
        }
        val missing = directory / "absent"
        val absent = rawHelper(listOf("--path", missing.toString()))
        absent.event("ERROR")
        assertEquals(4, absent.awaitExit())
        assertFalse(fs.exists(missing))
        for (size in listOf(0, 3, 5)) {
            val path = directory / "size-$size"
            fs.write(path) { write(ByteArray(size)) }
            val h = rawHelper(listOf("--path", path.toString()))
            h.event("ERROR")
            assertEquals(2, h.awaitExit())
            assertEquals(size.toLong(), fs.metadata(path).size)
        }
        assertEquals(42, counter().getValue())
    }
    @Test fun helperRejectsInvalidCommandsWithoutIncrementing() {
        val c = counter()
        for (command in listOf("1 RUN -1", "1 RUN 1000001", "1 INC abc", "1 WHAT", "0 INC", "1 READ extra", "1 INC ${Long.MAX_VALUE}")) {
            val h = helper()
            h.send(command)
            val event = CounterEvent(h.nextLine())
            assertEquals("ERROR", event.name)
            assertEquals(2, h.awaitExit())
            assertEquals(0, c.getValue())
        }
    }
    @Test fun helperRejectsRepeatedRequestId() {
        counter()
        val h = helper()
        assertEquals(0, value(h, 1, "READ"))
        h.send("1 INC")
        h.event("ERROR", 1)
        assertEquals(2, h.awaitExit())
    }
}
