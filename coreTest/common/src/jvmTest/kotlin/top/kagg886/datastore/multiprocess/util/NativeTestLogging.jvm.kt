package top.kagg886.datastore.multiprocess.util

internal actual fun allowVerboseLog(): Boolean =
    (System.getenv("DATASTORE_TEST_LOG_LEVEL")?.toIntOrNull() ?: 3) <= 1
