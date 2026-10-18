package top.kagg886.datastore.multiprocess.testhelper

import java.io.PrintStream

private val input = System.`in`.bufferedReader(Charsets.UTF_8)
private val output = PrintStream(System.out, true, Charsets.UTF_8)
private val diagnostics = PrintStream(System.err, true, Charsets.UTF_8)

internal actual fun readCommandLine(): String? = input.readLine()
internal actual fun emitLine(line: String) { output.println(line) }
internal actual fun emitDiagnostic(line: String) { diagnostics.println(line) }
internal actual fun verboseLogging(): Boolean =
    (System.getenv("DATASTORE_TEST_LOG_LEVEL")?.toIntOrNull() ?: 3) <= 1
