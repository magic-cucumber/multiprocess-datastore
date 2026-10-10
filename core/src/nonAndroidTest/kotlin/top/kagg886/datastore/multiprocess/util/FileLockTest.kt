package top.kagg886.datastore.multiprocess.util

import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import okio.FileSystem
import okio.Path
import okio.SYSTEM

class FileLockTest {
    private lateinit var directory: Path
    private lateinit var file: Path
    private lateinit var original: ByteArray
    private lateinit var other: Path
    private lateinit var otherOriginal: ByteArray
    private val locks = mutableListOf<FileLock>()
    private val processes = mutableListOf<LockHelperProcess>()
    private val fs = FileSystem.SYSTEM

    @BeforeTest
    fun beforeTest() {
        directory = lockTestDirectory()
        file = directory / "随机 binary lock.bin"
        original = Random.nextBytes(8192)
        fs.write(file) { write(original) }
        other = directory / "other.bin"
        otherOriginal = Random.nextBytes(4096)
        fs.write(other) { write(otherOriginal) }
    }

    @AfterTest
    fun afterTest() {
        try {
            val failures = processes.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
            if (failures.isNotEmpty()) throw failures.first()
                .also { first -> failures.drop(1).forEach(first::addSuppressed) }
        } finally {
            try {
                locks.forEach { it.close() }
                assertContentEquals(original, fs.read(file) { readByteArray() }, "Locking changed file contents")
                assertContentEquals(otherOriginal, fs.read(other) { readByteArray() }, "Locking changed the other file")
            } finally {
                fs.deleteRecursively(directory)
            }
        }
    }

    private fun remember(lock: FileLock?): FileLock = assertNotNull(lock).also { locks += it }
    private fun helper(
        shared: Boolean = false, duration: Long? = null, path: Path = file,
        acquireTimeout: Long = 5000
    ): LockHelperProcess {
        val args = listOf(
            if (duration == null) "hold" else "hold-for",
            "--path",
            path.toString(),
            "--mode",
            if (shared) "shared" else "exclusive",
            "--acquire-timeout-ms",
            acquireTimeout.toString()
        ) + if (duration == null) emptyList() else listOf("--duration-ms", duration.toString())
        return startLockHelper(args).also { processes += it }
    }

    @Test
    fun tryExclusiveOnIdleFile() {
        remember(FileLock.tryLock(file, false))
    }

    @Test
    fun trySharedOnIdleFile() {
        remember(FileLock.tryLock(file, true))
    }

    @Test
    fun waitExclusiveOnIdleFile() {
        remember(FileLock.lock(file, false, 1.seconds))
    }

    @Test
    fun waitSharedOnIdleFile() {
        remember(FileLock.lock(file, true, 1.seconds))
    }

    @Test
    fun missingFileIsRejected() {
        val missing = directory / "missing.bin"
        for (shared in listOf(false, true)) {
            assertFailsWith<IllegalArgumentException> { FileLock.tryLock(missing, shared) }
            assertFailsWith<IllegalArgumentException> { FileLock.lock(missing, shared, 1.seconds) }
        }
        assertFalse(fs.exists(missing))
    }

    @Test
    fun nonPositiveWholeMillisecondsAreRejected() {
        for (wait in listOf(Duration.ZERO, (-1).milliseconds, 0.5.milliseconds)) {
            for (shared in listOf(false, true)) {
                assertFailsWith<IllegalArgumentException> { FileLock.lock(file, shared, wait) }
            }
        }
    }

    private fun externalHolder(holderShared: Boolean, requestShared: Boolean) {
        val process = helper(holderShared)
        process.awaitEvent("LOCKED")
        val started = TimeSource.Monotonic.markNow()
        val acquired = FileLock.tryLock(file, requestShared)
        if (holderShared && requestShared) remember(acquired) else assertNull(acquired)
        assertTrue(started.elapsedNow() < 2.seconds, "tryLock blocked")
        process.stop()
        process.awaitEvent("RELEASED")
        assertEquals(0, process.awaitExit())
        acquired?.close()
        remember(FileLock.tryLock(file, false))
    }

    @Test
    fun externalSharedAllowsShared() = externalHolder(true, true)
    @Test
    fun externalSharedRejectsExclusive() = externalHolder(true, false)
    @Test
    fun externalExclusiveRejectsShared() = externalHolder(false, true)
    @Test
    fun externalExclusiveRejectsExclusive() = externalHolder(false, false)

    private fun kotlinHolder(holderShared: Boolean, requestShared: Boolean) {
        val lock = remember(FileLock.tryLock(file, holderShared))
        val process = helper(requestShared)
        process.awaitEvent("ATTEMPTING")
        if (holderShared && requestShared) {
            process.awaitEvent("LOCKED")
        } else {
            assertFailsWith<LockEventTimeout> { process.awaitEvent("LOCKED", 200) }
            lock.close()
            process.awaitEvent("LOCKED")
        }
        process.stop()
        process.awaitEvent("RELEASED")
        assertEquals(0, process.awaitExit())
        lock.close()
        remember(FileLock.tryLock(file, false))
    }

    @Test
    fun kotlinSharedAllowsShared() = kotlinHolder(true, true)
    @Test
    fun kotlinSharedRejectsExclusive() = kotlinHolder(true, false)
    @Test
    fun kotlinExclusiveRejectsShared() = kotlinHolder(false, true)
    @Test
    fun kotlinExclusiveRejectsExclusive() = kotlinHolder(false, false)

    @Test
    fun waitingTimesOutUnderConflict() {
        val process = helper()
        process.awaitEvent("LOCKED")
        for (shared in listOf(false, true)) {
            val started = TimeSource.Monotonic.markNow()
            assertNull(FileLock.lock(file, shared, 300.milliseconds))
            val elapsed = started.elapsedNow()
            assertTrue(elapsed >= 200.milliseconds && elapsed < 3.seconds, "Unexpected timeout duration: $elapsed")
        }
        process.stop()
        process.awaitEvent("RELEASED")
        assertEquals(0, process.awaitExit())
        remember(FileLock.tryLock(file, false))
    }

    @Test
    fun waitingAcquiresAfterTimedRelease() {
        val process = helper(duration = 500)
        val acquired = process.awaitEvent("LOCKED")
        remember(FileLock.lock(file, false, 3.seconds))
        val released = process.awaitEvent("RELEASED")
        assertTrue(acquired.epochMillis > 0)
        assertTrue(
            released.elapsedNanos - acquired.elapsedNanos >= 500_000_000,
            "hold-for released prematurely"
        )
        assertEquals(0, process.awaitExit())
    }

    @Test
    fun waitingSharedAllowsAnotherSharedLock() {
        val process = helper(true)
        process.awaitEvent("LOCKED")
        remember(FileLock.lock(file, true, 1.seconds))
    }

    @Test
    fun waitingExclusiveTimesOutUnderSharedLock() {
        val process = helper(true)
        process.awaitEvent("LOCKED")
        assertNull(FileLock.lock(file, false, 200.milliseconds))
        process.stop()
        process.awaitEvent("RELEASED")
        assertEquals(0, process.awaitExit())
        remember(FileLock.lock(file, false, 1.seconds))
    }

    @Test
    fun helperReleasesOnStdinEof() {
        val process = helper()
        process.awaitEvent("LOCKED")
        process.closeStdin()
        process.awaitEvent("RELEASED")
        assertEquals(0, process.awaitExit())
        remember(FileLock.tryLock(file, false))
    }

    @Test
    fun helperRejectsInvalidArguments() {
        val process = startLockHelper(
            listOf(
                "hold-for", "--path", file.toString(),
                "--mode", "exclusive", "--duration-ms", "-1"
            )
        ).also { processes += it }
        assertEquals(2, process.awaitExit())
        remember(FileLock.tryLock(file, false))
    }

    @Test
    fun helperDoesNotCreateMissingFiles() {
        val missing = directory / "missing.bin"
        val process = helper(path = missing)
        assertEquals(4, process.awaitExit())
        assertFalse(fs.exists(missing))
    }

    @Test
    fun allSharedHoldersMustRelease() {
        val first = helper(true)
        first.awaitEvent("LOCKED")
        val second = helper(true)
        second.awaitEvent("LOCKED")
        assertNull(FileLock.tryLock(file, false))
        first.stop()
        first.awaitEvent("RELEASED")
        assertEquals(0, first.awaitExit())
        assertNull(FileLock.tryLock(file, false))
        second.stop()
        second.awaitEvent("RELEASED")
        assertEquals(0, second.awaitExit())
        remember(FileLock.tryLock(file, false))
    }

    @Test
    fun closeIsIdempotent() {
        val lock = remember(FileLock.tryLock(file, false))
        assertFalse(lock.released.load())
        lock.close()
        assertTrue(lock.released.load())
        val next = remember(FileLock.tryLock(file, false))
        repeat(10) { lock.close() }
        // A stale close must not release the descriptor/handle reused by the new lock.
        val process = helper(acquireTimeout = 100)
        process.awaitEvent("ATTEMPTING")
        assertEquals(3, process.awaitExit())
        next.close()
        remember(FileLock.tryLock(file, false))
    }

    @Test
    fun terminatingProcessReleasesLock() {
        val process = helper()
        process.awaitEvent("LOCKED")
        process.terminate()
        assertNotEquals(0, process.awaitExit())
        remember(FileLock.tryLock(file, false))
    }

    @Test
    fun concurrentCloseReleasesExactlyOnce() {
        val releases = AtomicInt(0)
        val fake = object : FileLock {
            override val released = AtomicBoolean(false)
            override fun release() {
                releases.fetchAndAdd(1)
            }
        }
        closeConcurrently(fake)
        assertEquals(1, releases.load())
        assertTrue(fake.released.load())
        val actual = remember(FileLock.tryLock(file, false))
        closeConcurrently(actual)
        assertTrue(actual.released.load())
        remember(FileLock.tryLock(file, false))
    }

    @Test
    fun locksOnDifferentFilesAreIndependent() {
        val process = helper()
        process.awaitEvent("LOCKED")
        remember(FileLock.tryLock(other, false))
        assertNull(FileLock.tryLock(file, false))
    }

    @Test
    fun repeatedContentionAndRecovery() {
        val process = helper()
        process.awaitEvent("LOCKED")
        repeat(50) { assertNull(FileLock.tryLock(file, false)) }
        repeat(5) { assertNull(FileLock.lock(file, false, 10.milliseconds)) }
        process.stop()
        process.awaitEvent("RELEASED")
        assertEquals(0, process.awaitExit())
        repeat(50) { remember(FileLock.tryLock(file, false)).close() }
    }
}
