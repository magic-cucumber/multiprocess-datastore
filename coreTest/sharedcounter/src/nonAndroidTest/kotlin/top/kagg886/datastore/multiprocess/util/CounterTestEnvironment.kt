@file:OptIn(VisibleForTest::class)

package top.kagg886.datastore.multiprocess.util

import okio.Path

internal expect fun counterTestDirectory(): Path
internal expect fun startCounterHelper(arguments: List<String>): CounterHelperProcess
internal expect fun counterEpochMillis(): Long
internal expect fun counterWaitUntil(epochMillis: Long)
internal expect fun counterConcurrently(actions: List<() -> Unit>)

internal interface CounterHelperProcess : AutoCloseable {
    fun send(command: String)
    fun nextLine(timeoutMillis: Long = 10_000): String
    fun closeStdin()
    fun awaitExit(timeoutMillis: Long = 10_000): Int
    fun terminate()
}

// Only parses the helper's deliberately fixed JSON schema; no general-purpose JSON dependency.
internal class CounterEvent(val raw: String) {
    val name: String = Regex("\"event\":\"([A-Z]+)\"").find(raw)?.groupValues?.get(1)
        ?: error("Invalid helper event: $raw")
    init { check(raw.startsWith("{") && raw.endsWith("}")) { "Invalid helper JSON: $raw" } }
    fun number(field: String): Long = optionalNumber(field) ?: error("Missing $field: $raw")
    fun optionalNumber(field: String): Long? = Regex("\"$field\":(-?\\d+)(?=[,}])")
        .find(raw)?.groupValues?.get(1)?.toLong()
    fun values(): List<Int> {
        val text = Regex("\"values\":\\[([^]]*)]").find(raw)?.groupValues?.get(1)
            ?: error("Missing values: $raw")
        return if (text.isEmpty()) emptyList() else text.split(',').map(String::toInt)
    }
}

internal fun CounterHelperProcess.event(name: String, id: Long? = null): CounterEvent {
    val event = CounterEvent(nextLine())
    check(event.name == name && event.optionalNumber("id") == id) {
        "Expected $name/$id, received ${event.raw}"
    }
    return event
}
