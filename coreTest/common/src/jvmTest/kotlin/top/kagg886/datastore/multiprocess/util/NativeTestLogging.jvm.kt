package top.kagg886.datastore.multiprocess.util

internal actual fun nativeTestDebugLoggingEnabled(): Boolean =
    System.getenv("DATASTORE_TEST_DEBUG").toBoolean()
