@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package top.kagg886.datastore.multiprocess.testhelper

import platform.posix.fflush
import platform.posix.stdout
import platform.posix.stderr
import platform.posix.fputs
import platform.posix.getenv
import kotlinx.cinterop.toKString

internal actual fun readCommandLine(): String? = readlnOrNull()
internal actual fun emitLine(line: String) { println(line); fflush(stdout) }
internal actual fun emitDiagnostic(line: String) { fputs("$line\n", stderr); fflush(stderr) }
internal actual fun verboseLogging(): Boolean =
    (getenv("DATASTORE_TEST_LOG_LEVEL")?.toKString()?.toIntOrNull() ?: 3) <= 1
