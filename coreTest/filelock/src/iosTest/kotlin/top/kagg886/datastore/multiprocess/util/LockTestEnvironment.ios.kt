@file:OptIn(
    top.kagg886.datastore.multiprocess.util.VisibleForTest::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package top.kagg886.datastore.multiprocess.util

import kotlinx.cinterop.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.*
import top.kagg886.datastore.multiprocess.testspawn.*
import kotlin.concurrent.atomics.AtomicInt

private class CloseTask(val lock: FileLock, val ready: AtomicInt, val start: AtomicInt)

internal actual fun closeConcurrently(lock: FileLock) = memScoped {
    val ready = AtomicInt(0)
    val start = AtomicInt(0)
    val context = StableRef.create(CloseTask(lock, ready, start))
    val threads = allocArray<pthread_tVar>(8)
    var created = 0
    try {
        repeat(8) { i ->
            val result = pthread_create(threads + i, null, staticCFunction { pointer: COpaquePointer? ->
                val task = pointer!!.asStableRef<CloseTask>().get()
                task.ready.fetchAndAdd(1)
                while (task.start.load() == 0) usleep(100u)
                task.lock.close()
                null as COpaquePointer?
            }, context.asCPointer())
            check(result == 0) { "pthread_create failed: $result" }
            created++
        }
        while (ready.load() < 8) usleep(100u)
    } finally {
        start.store(1)
        repeat(created) { check(pthread_join(threads[it], null) == 0) }
        context.dispose()
    }
}

internal actual fun lockTestDirectory(): Path = memScoped {
    val base = getenv("TMPDIR")?.toKString() ?: "/tmp"
    val template = "$base/file-lock-test-XXXXXX".cstr.getPointer(this)
    checkNotNull(mkdtemp(template)) { "mkdtemp failed: $errno" }.toKString().toPath()
}

internal actual fun startLockHelper(arguments: List<String>): LockHelperProcess = memScoped {
    val executable = checkNotNull(getenv("FILE_LOCK_HELPER")?.toKString()) {
        "FILE_LOCK_HELPER is not configured; run iosSimulatorArm64Test"
    }
    val input = allocArray<IntVar>(2)
    val output = allocArray<IntVar>(2)
    check(pipe(input) == 0) { "stdin pipe failed: $errno" }
    if (pipe(output) != 0) {
        close(input[0]); close(input[1])
        error("stdout pipe failed: $errno")
    }
    val actions = alloc<posix_spawn_file_actions_tVar>()
    var initialized = false
    var spawned = false
    try {
        check(posix_spawn_file_actions_init(actions.ptr) == 0)
        initialized = true
        check(posix_spawn_file_actions_adddup2(actions.ptr, input[0], STDIN_FILENO) == 0)
        check(posix_spawn_file_actions_adddup2(actions.ptr, output[1], STDOUT_FILENO) == 0)
        check(posix_spawn_file_actions_adddup2(actions.ptr, output[1], STDERR_FILENO) == 0)
        for (fd in listOf(input[0], input[1], output[0], output[1])) {
            check(posix_spawn_file_actions_addclose(actions.ptr, fd) == 0)
            check(fcntl(fd, F_SETFD, FD_CLOEXEC) == 0)
        }
        val argv = allocArray<CPointerVar<ByteVar>>(arguments.size + 2)
        (listOf(executable) + arguments).forEachIndexed { i, value -> argv[i] = value.cstr.getPointer(this) }
        argv[arguments.size + 1] = null
        val pid = alloc<pid_tVar>()
        val result = posix_spawn(pid.ptr, executable, actions.ptr, null, argv, lock_test_environ())
        check(result == 0) { "posix_spawn failed: $result ($executable)" }
        spawned = true
        IosLockHelper(pid.value, input[1], output[0])
    } finally {
        if (initialized) posix_spawn_file_actions_destroy(actions.ptr)
        close(input[0]); close(output[1])
        if (!spawned) { close(input[1]); close(output[0]) }
    }
}

private class IosLockHelper(private val pid: Int, private val input: Int, private val output: Int) : LockHelperProcess {
    private val transcript = mutableListOf<String>()
    private val partialLine = StringBuilder()
    private var exitCode: Int? = null
    private var closed = false
    private var stdinClosed = false

    override fun awaitEvent(name: String, timeoutMillis: Long): LockEvent = memScoped {
        val started = TimeSource.Monotonic.markNow()
        val descriptor = alloc<pollfd>()
        descriptor.fd = output
        descriptor.events = POLLIN.toShort()
        val byte = alloc<ByteVar>()
        while (true) {
            val remaining = timeoutMillis - started.elapsedNow().inWholeMilliseconds
            if (remaining <= 0) throw LockEventTimeout("Waiting for $name: $transcript")
            val result = poll(descriptor.ptr, 1u, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (result < 0 && errno == EINTR) continue
            check(result >= 0) { "poll failed: $errno" }
            if (result == 0) continue
            val count = read(output, byte.ptr, 1u)
            if (count < 0 && errno == EINTR) continue
            check(count == 1L) { "Helper exited before $name: $transcript" }
            if (byte.value.toInt() == 10) {
                val line = partialLine.toString()
                partialLine.clear()
                transcript += line
                val event = LockEvent.parse(line)
                if (event?.name == name) return@memScoped event
            } else {
                partialLine.append(byte.value.toInt().toChar())
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    override fun stop() = memScoped {
        val command = "STOP\n".cstr.getPointer(this)
        check(write(input, command, 5u) == 5L) { "STOP write failed: $errno" }
    }

    override fun closeStdin() {
        if (!stdinClosed) { close(input); stdinClosed = true }
    }

    override fun awaitExit(timeoutMillis: Long): Int = memScoped {
        exitCode?.let { return@memScoped it }
        val started = TimeSource.Monotonic.markNow()
        val status = alloc<IntVar>()
        while (started.elapsedNow() < timeoutMillis.milliseconds) {
            val result = waitpid(pid, status.ptr, WNOHANG)
            if (result == pid) {
                // Darwin wait status: low 7 bits contain the signal; high byte is normal exit status.
                val signal = status.value and 0x7f
                val code = if (signal == 0) (status.value shr 8) and 0xff else 128 + signal
                exitCode = code
                return@memScoped code
            }
            if (result < 0 && errno == EINTR) continue
            check(result >= 0) { "waitpid failed: $errno" }
            usleep(1000u)
        }
        error("Helper did not exit: $transcript")
    }

    override fun terminate() { if (exitCode == null) kill(pid, SIGKILL) }
    override fun close() {
        if (closed) return
        try { terminate(); awaitExit(5000) } finally {
            closeStdin(); close(output)
            closed = true
        }
    }
}
