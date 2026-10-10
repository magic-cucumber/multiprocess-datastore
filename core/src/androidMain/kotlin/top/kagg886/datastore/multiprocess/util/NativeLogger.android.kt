package top.kagg886.datastore.multiprocess.util

// The desktop JNI logger is not loaded on Android.
internal actual fun platformSetNativeLoggerMinLevel(level: Int) = Unit
