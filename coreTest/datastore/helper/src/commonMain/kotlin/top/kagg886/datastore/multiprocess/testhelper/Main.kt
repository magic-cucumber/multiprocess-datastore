package top.kagg886.datastore.multiprocess.testhelper

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toPath
import top.kagg886.datastore.multiprocess.util.NativeLogger

internal expect fun emitLine(line: String)
internal expect fun emitDiagnostic(line: String)
internal expect fun verboseLogging(): Boolean

/** stdout is a request-ID protocol; logs and stack traces go to stderr. */
fun main(args: Array<String>) = runBlocking {
    NativeLogger.unregister(NativeLogger.DefaultLogger)
    NativeLogger.register { level, tag, message -> emitDiagnostic("$level/$tag: $message") }
    NativeLogger.setMinLevel(if (verboseLogging()) NativeLogger.Level.VERBOSE else NativeLogger.Level.INFO)
    val storeJob = SupervisorJob()
    val storeScope = CoroutineScope(Dispatchers.Default + storeJob)
    require(args.size == 1 || (args.size == 2 && args[1] == "--no-initializers"))
    val store = testStore(args[0].toPath(), storeScope, withoutInitializers = args.size == 2)
    val output = Mutex()
    suspend fun event(id: String, name: String, value: TestState? = null) = output.withLock {
        emitLine(listOf("@DS", id, name, value?.count ?: "", value?.text ?: "", value?.flag ?: "").joinToString("\t"))
    }
    val gates = mutableMapOf<String, CompletableDeferred<String>>()
    val jobs = mutableMapOf<String, Job>()
    try {
        event("0", "READY")
        while (true) {
            val line = withContext(Dispatchers.Default) { readlnOrNull() } ?: break
            val fields = line.split('\t')
            val id = fields[0]
            val command = fields[1]
            when (command) {
                "EXIT" -> { event(id, "BYE"); break }
                "FINISH" -> {
                    checkNotNull(gates[fields[2]]).complete(fields[3])
                    event(id, "DONE")
                }
                "CANCEL" -> {
                    checkNotNull(jobs[fields[2]]).cancel()
                    event(id, "DONE")
                }
                else -> {
                    val gate = if (command in listOf("HOLD", "INTERLEAVED")) CompletableDeferred<String>() else null
                    if (gate != null) gates[id] = gate
                    jobs[id] = launch(Dispatchers.Default) {
                        try {
                            event(id, "REQUESTED")
                            val value = when (command) {
                                "GET" -> store.data.first()
                                "TEXT" -> store.updateData { it.copy(text = fields[2]) }
                                "ADD" -> {
                                    repeat(fields[2].toInt()) {
                                        store.updateData { it.copy(count = it.count + 1) }
                                    }
                                    store.data.first()
                                }
                                "INTERLEAVED" -> {
                                    val incremented = store.updateData { it.copy(count = it.count + 1) }
                                    event(id, "UPDATED", incremented)
                                    check(gate!!.await() == "COMMIT")
                                    event(id, "WILL_WRITE")
                                    store.updateData {
                                        event(id, "LOCKED", it)
                                        it.copy(flag = true)
                                    }
                                }
                                "HOLD" -> store.updateData {
                                    event(id, "LOCKED")
                                    when (gate!!.await()) {
                                        "FAIL" -> error("Intentional transform failure")
                                        "COMMIT" -> it.copy(text = fields[2])
                                        else -> error("Unknown finish mode")
                                    }
                                }
                                else -> error("Unknown command: $command")
                            }
                            event(id, "VALUE", value)
                        } catch (cancelled: CancellationException) {
                            withContext(NonCancellable) { event(id, "CANCELLED") }
                        } catch (error: Throwable) {
                            withContext(NonCancellable) { event(id, "ERROR") }
                            // Intentional failures are observed through ERROR, unexpected failures
                            // retain their stack trace for parent-process diagnostics.
                            if (command != "HOLD") error.printStackTrace()
                        }
                    }
                }
            }
        }
    } finally {
        jobs.values.forEach { it.cancel() }
        jobs.values.joinAll()
        storeJob.cancelAndJoin()
    }
}
