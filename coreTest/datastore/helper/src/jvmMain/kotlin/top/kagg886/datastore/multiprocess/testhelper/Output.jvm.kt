package top.kagg886.datastore.multiprocess.testhelper

internal actual fun emitLine(line: String) { println(line); System.out.flush() }
internal actual fun emitDiagnostic(line: String) { System.err.println(line) }
internal actual fun verboseLogging(): Boolean =
    (System.getenv("DATASTORE_TEST_LOG_LEVEL")?.toIntOrNull() ?: 3) <= 1
