package top.kagg886.datastore.multiprocess.util

import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import okio.FileSystem
import okio.IOException
import okio.SYSTEM

class NativeLoggingTest : NativeLoggingTestFixture() {
    private data class Entry(val level: NativeLogger.Level, val tag: String, val message: String)

    @Test
    fun realOperationsProduceAllLevelsWithoutRepeatingResultsAcrossLayers() {
        val entries = AtomicReference<List<Entry>>(emptyList())
        val receiver = NativeLogger { level, tag, message ->
            val entry = Entry(level, tag, message)
            while (true) {
                val before = entries.load()
                if (entries.compareAndSet(before, before + entry)) break
            }
        }
        val directory = counterTestDirectory()
        val path = directory / "日志 trace counter.bin"
        var counter: SharedCounter? = null
        var held: FileLock? = null
        NativeLogger.register(receiver)
        try {
            NativeLogger.setMinLevel(NativeLogger.Level.VERBOSE)
            val value = SharedCounter.new(path).also { counter = it }
            assertEquals(0, value.getValue())
            assertEquals(1, value.incrementAndGetValue())
            held = assertNotNull(FileLock.tryLock(path, false))
            assertNull(FileLock.tryLock(path, false))
            assertNull(FileLock.lock(path, false, 20.milliseconds))
            held.close()
            held = null
            value.close()
            value.close()
            assertFailsWith<IOException> { value.getValue() }

            // JVM native logs are asynchronous. The final ERROR follows earlier native logs
            // in the same queue; wait for delivery rather than relying on a fixed sleep.
            val started = TimeSource.Monotonic.markNow()
            while (entries.load().none { it.level == NativeLogger.Level.ERROR && "closed" in it.message }) {
                assertTrue(started.elapsedNow() < 5_000.milliseconds, "Missing closed-counter diagnostic")
                counterWaitUntil(counterEpochMillis() + 5)
            }
            val trace = entries.load()
            val required = setOf(NativeLogger.Level.VERBOSE, NativeLogger.Level.DEBUG,
                NativeLogger.Level.INFO, NativeLogger.Level.WARN, NativeLogger.Level.ERROR)
            assertTrue(trace.map { it.level }.toSet().containsAll(required), trace.toString())
            assertTrue(trace.any { path.toString() in it.message }, "Missing file context")
            assertEquals(1, trace.count { it.message.contains("read", true) && it.message.contains("value=0") })
            assertEquals(1, trace.count { it.message.contains("increment", true) && it.message.contains("value=1") })
            assertEquals(1, trace.count { it.message.contains("wait exhausted", true) })
            assertEquals(1, trace.count { it.message.contains("close requested", true) })
            assertEquals(1, trace.count { it.level == NativeLogger.Level.ERROR && "closed" in it.message })
        } finally {
            try {
                held?.close()
                counter?.close()
            } finally {
                configureNativeTestLogging()
                NativeLogger.unregister(receiver)
                FileSystem.SYSTEM.deleteRecursively(directory)
            }
        }
    }
}
