package top.kagg886.datastore.multiprocess.util

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference

/** Receives native logs. JVM callbacks run on the native logger thread, so avoid blocking. */
public fun interface NativeLogger {
    public enum class Level(public val code: Int) {
        VERBOSE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4), ASSERT(5);

        public companion object {
            public fun from(code: Int): Level = entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown native log level: $code")
        }
    }

    public fun onLoggerReceived(level: Level, tag: String, message: String)

    public companion object {
        private val receivers = AtomicReference<List<NativeLogger>>(emptyList())
        private val minLevel = AtomicInt(Level.INFO.code)

        /** No default output is installed; applications choose their own log sink. */
        public fun register(receiver: NativeLogger) {
            while (true) {
                val current = receivers.load()
                check(receiver !in current) { "Receiver already registered" }
                if (receivers.compareAndSet(current, current + receiver)) return
            }
        }

        /** An in-flight dispatch can still notify a receiver after it is unregistered. */
        public fun unregister(receiver: NativeLogger) {
            while (true) {
                val current = receivers.load()
                check(receiver in current) { "Receiver not registered" }
                if (receivers.compareAndSet(current, current - receiver)) return
            }
        }

        /** Updates the Kotlin threshold and, on JVM, the native threshold. Defaults to INFO. */
        public fun setMinLevel(level: Level) {
            platformSetNativeLoggerMinLevel(level.code)
            minLevel.store(level.code)
        }

        /** Dispatches to a snapshot of receivers; a failing receiver does not stop the others. */
        public fun log(level: Level, tag: String, message: String) {
            if (level.code < minLevel.load()) return
            receivers.load().forEach { receiver ->
                runCatching { receiver.onLoggerReceived(level, tag, message) }
            }
        }
    }
}

internal expect fun platformSetNativeLoggerMinLevel(level: Int)
