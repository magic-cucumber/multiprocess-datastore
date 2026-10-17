package top.kagg886.datastore.multiprocess.util

import okio.Path

internal expect fun observerTestDirectory(): Path
internal expect fun startObserverHelper(arguments: List<String>): ObserverHelperProcess
internal expect fun observerPause(millis: Long)
internal expect fun observerConcurrently(actions: List<() -> Unit>)

internal interface ObserverHelperProcess : AutoCloseable {
    fun send(command: String)
    fun nextLine(timeoutMillis: Long = 10_000): String
    fun closeStdin()
    fun awaitExit(timeoutMillis: Long = 10_000): Int
    fun terminate()
}

// Only parses the helper's deliberately fixed JSON schema; no general-purpose JSON dependency.
internal class ObserverEvent(val raw: String) {
    val name: String = Regex("\"event\":\"([A-Z]+)\"").find(raw)?.groupValues?.get(1)
        ?: error("Invalid helper event: $raw")
    init { check(raw.startsWith("{") && raw.endsWith("}")) { "Invalid helper JSON: $raw" } }
    fun number(field: String): Long = optionalNumber(field) ?: error("Missing $field: $raw")
    fun optionalNumber(field: String): Long? = Regex("\"$field\":(-?\\d+)(?=[,}])")
        .find(raw)?.groupValues?.get(1)?.toLong()
}
internal fun ObserverHelperProcess.event(name: String, id: Long? = null): ObserverEvent {
    val event = ObserverEvent(nextLine())
    check(event.name == name && event.optionalNumber("id") == id) {
        "Expected $name/$id, received ${event.raw}"
    }
    return event
}
