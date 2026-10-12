@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.datastore.multiprocess.util

import kotlinx.cinterop.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.TimeSource
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.*
import top.kagg886.datastore.multiprocess.countertestspawn.*

internal actual fun counterEpochMillis(): Long = memScoped {
    val time = alloc<timeval>()
    check(gettimeofday(time.ptr, null) == 0)
    time.tv_sec * 1000 + time.tv_usec / 1000
}
internal actual fun counterWaitUntil(epochMillis: Long) {
    val remaining = epochMillis - counterEpochMillis()
    val started = TimeSource.Monotonic.markNow()
    if (remaining > 2) usleep(((remaining - 2) * 1000).toUInt())
    while (started.elapsedNow().inWholeNanoseconds < remaining * 1_000_000) { }
}
internal actual fun counterTestDirectory(): Path = memScoped {
    val base = getenv("TMPDIR")?.toKString() ?: "/tmp"
    val template = "$base/shared-counter-test-XXXXXX".cstr.getPointer(this)
    checkNotNull(mkdtemp(template)) { "mkdtemp failed: $errno" }.toKString().toPath()
}

private class ConcurrentTask(
    val action: () -> Unit, val ready: AtomicInt, val start: AtomicInt,
    val failure: AtomicReference<Throwable?>,
)
internal actual fun counterConcurrently(actions: List<() -> Unit>): Unit = memScoped {
    val ready = AtomicInt(0)
    val start = AtomicInt(0)
    val failure = AtomicReference<Throwable?>(null)
    val contexts = actions.map { StableRef.create(ConcurrentTask(it, ready, start, failure)) }
    val threads = allocArray<pthread_tVar>(actions.size)
    var created = 0
    try {
        contexts.forEachIndexed { i, context ->
            val result = pthread_create(threads + i, null, staticCFunction { pointer: COpaquePointer? ->
                val task = pointer!!.asStableRef<ConcurrentTask>().get()
                task.ready.fetchAndAdd(1)
                while (task.start.load() == 0) usleep(100u)
                try { task.action() } catch (error: Throwable) { task.failure.compareAndSet(null, error) }
                null as COpaquePointer?
            }, context.asCPointer())
            check(result == 0) { "pthread_create failed: $result" }
            created++
        }
        val started = TimeSource.Monotonic.markNow()
        while (ready.load() < actions.size) {
            check(started.elapsedNow().inWholeMilliseconds < 10_000) { "Workers did not become ready" }
            usleep(100u)
        }
    } finally {
        start.store(1)
        repeat(created) { check(pthread_join(threads[it], null) == 0) }
        contexts.forEach { it.dispose() }
    }
    failure.load()?.let { throw it }
}

internal actual fun startCounterHelper(arguments: List<String>): CounterHelperProcess = memScoped {
    val executable = checkNotNull(getenv("SHARED_COUNTER_HELPER")?.toKString()) {
        "SHARED_COUNTER_HELPER is not configured; run iosSimulatorArm64Test"
    }
    val input = allocArray<IntVar>(2)
    val output = allocArray<IntVar>(2)
    check(pipe(input) == 0) { "stdin pipe failed: $errno" }
    if (pipe(output) != 0) {
        close(input[0]); close(input[1]); error("stdout pipe failed: $errno")
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
        val result = posix_spawn(pid.ptr, executable, actions.ptr, null, argv, counter_test_environ())
        check(result == 0) { "posix_spawn failed: $result ($executable)" }
        spawned = true
        IosCounterHelper(pid.value, input[1], output[0])
    } finally {
        if (initialized) posix_spawn_file_actions_destroy(actions.ptr)
        close(input[0]); close(output[1])
        if (!spawned) { close(input[1]); close(output[0]) }
    }
}

private class IosCounterHelper(private val pid: Int, private val input: Int, private val output: Int) : CounterHelperProcess {
    private val transcript = mutableListOf<String>()
    private val partialLine = StringBuilder()
    private var exitCode: Int? = null
    private var closed = false
    private var stdinClosed = false
    override fun nextLine(timeoutMillis: Long): String = memScoped {
        val started = TimeSource.Monotonic.markNow()
        val descriptor = alloc<pollfd>()
        descriptor.fd = output
        descriptor.events = POLLIN.toShort()
        val byte = alloc<ByteVar>()
        while (true) {
            val remaining = timeoutMillis - started.elapsedNow().inWholeMilliseconds
            check(remaining > 0) { "Helper timeout: $transcript" }
            val result = poll(descriptor.ptr, 1u, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (result < 0 && errno == EINTR) continue
            check(result >= 0) { "poll failed: $errno" }
            if (result == 0) continue
            val count = read(output, byte.ptr, 1u)
            if (count < 0 && errno == EINTR) continue
            check(count == 1L) { "Helper EOF: $transcript" }
            if (byte.value.toInt() == 10) {
                val line = partialLine.toString()
                partialLine.clear()
                if (transcript.size == 32) transcript.removeAt(0)
                transcript += line
                // Like filelock, the native transport merges stderr; retain diagnostics
                // but return only protocol events. RUN buffers results until increments finish.
                if (line.startsWith("{")) return@memScoped line
            } else partialLine.append(byte.value.toInt().toChar())
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
    override fun send(command: String): Unit = memScoped {
        val bytes = (command + "\n").encodeToByteArray()
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = write(input, pinned.addressOf(offset), (bytes.size - offset).toULong())
                if (count < 0 && errno == EINTR) continue
                check(count > 0) { "Command write failed: $errno" }
                offset += count.toInt()
            }
        }
    }
    override fun closeStdin() { if (!stdinClosed) { close(input); stdinClosed = true } }
    override fun awaitExit(timeoutMillis: Long): Int = memScoped {
        exitCode?.let { return@memScoped it }
        val started = TimeSource.Monotonic.markNow()
        val status = alloc<IntVar>()
        while (started.elapsedNow().inWholeMilliseconds < timeoutMillis) {
            val result = waitpid(pid, status.ptr, WNOHANG)
            if (result == pid) {
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
    override fun terminate() { if (exitCode == null) check(kill(pid, SIGKILL) == 0 || errno == ESRCH) }
    override fun close() {
        if (closed) return
        try { terminate(); awaitExit(5000) } finally {
            closeStdin(); close(output); closed = true
        }
    }
}
