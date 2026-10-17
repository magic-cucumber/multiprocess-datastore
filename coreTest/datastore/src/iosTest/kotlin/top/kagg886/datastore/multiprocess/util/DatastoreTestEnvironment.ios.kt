@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.datastore.multiprocess.util

import kotlinx.cinterop.*
import kotlin.time.TimeSource
import okio.Path
import okio.Buffer
import okio.Path.Companion.toPath
import platform.posix.*
import top.kagg886.datastore.multiprocess.datastoretestspawn.*

internal actual fun datastoreTestDirectory(): Path = memScoped {
    val base = getenv("TMPDIR")?.toKString() ?: "/tmp"
    val template = "$base/datastore-test-XXXXXX".cstr.getPointer(this)
    checkNotNull(mkdtemp(template)) { "mkdtemp failed: $errno" }.toKString().toPath()
}

internal actual fun startDatastoreHelper(arguments: List<String>): DatastoreHelperProcess = memScoped {
    val executable = checkNotNull(getenv("DATASTORE_HELPER")?.toKString()) {
        "DATASTORE_HELPER is not configured; run iosSimulatorArm64Test"
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
        val result = posix_spawn(pid.ptr, executable, actions.ptr, null, argv, datastore_test_environ())
        check(result == 0) { "posix_spawn failed: $result ($executable)" }
        spawned = true
        IosDatastoreHelper(pid.value, input[1], output[0])
    } finally {
        if (initialized) posix_spawn_file_actions_destroy(actions.ptr)
        close(input[0]); close(output[1])
        if (!spawned) { close(input[1]); close(output[0]) }
    }
}

private class IosDatastoreHelper(private val pid: Int, private val input: Int, private val output: Int) : DatastoreHelperProcess {
    private val transcript = mutableListOf<String>()
    private val partialLine = Buffer()
    private val pendingLines = ArrayDeque<String>()
    private var exitCode: Int? = null
    private var closed = false
    private var stdinClosed = false
    override fun nextLine(timeoutMillis: Long): String = memScoped {
        if (pendingLines.isNotEmpty()) return@memScoped pendingLines.removeFirst()
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
                val line = partialLine.readUtf8().removeSuffix("\r")
                if (transcript.size == 32) transcript.removeAt(0)
                transcript += line
                // Retain diagnostics, but return only protocol events.
                if (line.startsWith("@DS\t")) return@memScoped line
            } else partialLine.writeByte(byte.value.toInt())
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
    override fun send(command: String): Unit = memScoped {
        if (transcript.size == 32) transcript.removeAt(0)
        transcript += "> " + command.take(256)
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
            drainHelperOutput(output, partialLine, pendingLines)
            val result = waitpid(pid, status.ptr, WNOHANG)
            if (result == pid) {
                drainHelperOutput(output, partialLine, pendingLines)
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
    override fun terminate() {
        if (exitCode == null) {
            check(kill(pid, SIGKILL) == 0 || errno == ESRCH)
        }
    }
    override fun close() {
        if (closed) return
        try { terminate(); awaitExit(5000) } finally {
            closeStdin(); close(output); closed = true
        }
    }
}


/** Nonblocking drain while reaping a child; preserve protocol lines for later consumers. */
private fun drainHelperOutput(fd: Int, partial: Buffer, pending: ArrayDeque<String>): Unit = memScoped {
    val descriptor = alloc<pollfd>()
    descriptor.fd = fd
    descriptor.events = POLLIN.toShort()
    val byte = alloc<ByteVar>()
    while (true) {
        val result = poll(descriptor.ptr, 1u, 0)
        if (result < 0 && errno == EINTR) continue
        check(result >= 0) { "Helper drain poll failed: $errno" }
        if (result == 0) break
        val count = read(fd, byte.ptr, 1u)
        if (count < 0 && errno == EINTR) continue
        check(count >= 0) { "Helper drain read failed: $errno" }
        if (count == 0L) break
        if (byte.value.toInt() == 10) {
            val line = partial.readUtf8().removeSuffix("\r")
            if (line.startsWith("@DS\t")) pending.addLast(line)
        } else partial.writeByte(byte.value.toInt())
    }
}

internal actual fun datastoreVerboseLogging(): Boolean =
    (getenv("DATASTORE_TEST_LOG_LEVEL")?.toKString()?.toIntOrNull() ?: 3) <= 1
