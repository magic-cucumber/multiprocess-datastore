package top.kagg886.datastore.multiprocess.util

/** Consumes only helper log records; native timestamps survive delayed pipe delivery. */
internal fun dispatchHelperLog(line: String): Boolean {
    if (!line.startsWith("LOG ")) return false
    val fields = line.split(' ', limit = 5)
    val level = when (fields.getOrNull(1)) {
        "V" -> NativeLogger.Level.VERBOSE
        "D" -> NativeLogger.Level.DEBUG
        "I" -> NativeLogger.Level.INFO
        "W" -> NativeLogger.Level.WARN
        "E" -> NativeLogger.Level.ERROR
        else -> null
    }
    if (fields.size != 5 || level == null || fields[2].toLongOrNull() == null) {
        NativeLogger.log(NativeLogger.Level.ERROR, "HelperTransport", "Malformed helper log: $line")
    } else {
        NativeLogger.log(level, fields[3], "native_epoch_us=${fields[2]} ${fields[4]}")
    }
    return true
}

internal expect fun testEpochMicros(): Long
