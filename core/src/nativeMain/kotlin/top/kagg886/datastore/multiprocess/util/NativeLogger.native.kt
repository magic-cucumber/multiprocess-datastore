package top.kagg886.datastore.multiprocess.util

// Kotlin/Native uses the common receiver API without the desktop JNI logger.
internal actual fun platformSetNativeLoggerMinLevel(level: Int) = Unit
