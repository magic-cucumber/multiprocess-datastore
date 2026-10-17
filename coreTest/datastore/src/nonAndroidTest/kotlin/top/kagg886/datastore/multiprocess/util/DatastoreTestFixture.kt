package top.kagg886.datastore.multiprocess.util

import androidx.datastore.core.DataStore
import kotlinx.coroutines.*
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.test.*
import kotlin.time.TimeSource
import top.kagg886.datastore.multiprocess.testhelper.TestState
import top.kagg886.datastore.multiprocess.testhelper.testStore

internal expect fun datastoreTestDirectory(): Path
internal expect fun startDatastoreHelper(arguments: List<String>): DatastoreHelperProcess

internal interface DatastoreHelperProcess : AutoCloseable {
    fun send(command: String)
    fun nextLine(timeoutMillis: Long = 10_000): String
    fun closeStdin()
    fun awaitExit(timeoutMillis: Long = 10_000): Int
    fun terminate()
}

internal data class StoreEvent(val id: Int, val name: String, val value: TestState?)

internal class StoreSession(val process: DatastoreHelperProcess) {
    private var nextId = 0
    private val pending = mutableListOf<StoreEvent>()
    fun send(vararg fields: String): Int {
        val id = ++nextId
        process.send((listOf(id.toString()) + fields).joinToString("\t"))
        return id
    }
    fun event(id: Int, name: String): StoreEvent {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val index = pending.indexOfFirst { it.id == id && it.name == name }
            if (index >= 0) return pending.removeAt(index)
            check(pending.none { it.id == id && it.name in listOf("ERROR", "CANCELLED") }) {
                "Expected $name/$id; pending=$pending"
            }
            val remaining = 10_000 - start.elapsedNow().inWholeMilliseconds
            check(remaining > 0) { "Expected $name/$id; pending=$pending" }
            val fields = process.nextLine(remaining).split('\t')
            check(fields.size == 6 && fields[0] == "@DS") { "Invalid helper event: $fields" }
            pending += StoreEvent(fields[1].toInt(), fields[2],
                fields[3].toIntOrNull()?.let { TestState(it, fields[4], fields[5].toBooleanStrict()) })
        }
    }
    fun value(id: Int): TestState = checkNotNull(event(id, "VALUE").value)
    fun read(): TestState = value(send("GET"))
    fun text(value: String): TestState = value(send("TEXT", value))
    fun hold(value: String): Int = send("HOLD", value).also { event(it, "LOCKED") }
    fun finish(id: Int, mode: String = "COMMIT") {
        event(send("FINISH", id.toString(), mode), "DONE")
    }
    fun exit() {
        event(send("EXIT"), "BYE")
        assertEquals(0, process.awaitExit())
    }
}

abstract class DatastoreTestFixture {
    protected open val withoutInitializers: Boolean = false
    internal lateinit var directory: Path
    internal lateinit var file: Path
    internal lateinit var storeScope: CoroutineScope
    internal lateinit var store: DataStore<TestState>
    private val helpers = mutableListOf<StoreSession>()

    @BeforeTest fun setup() {
        NativeLogger.setMinLevel(
            if (datastoreVerboseLogging()) NativeLogger.Level.VERBOSE else NativeLogger.Level.INFO
        )
        directory = datastoreTestDirectory()
        file = directory / "共享 datastore 文件.txt"
        storeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        store = testStore(file, storeScope, withoutInitializers)
    }

    @AfterTest fun cleanup(): Unit = runBlocking {
        val errors = mutableListOf<Throwable>()
        helpers.forEach { runCatching { it.process.close() }.exceptionOrNull()?.let(errors::add) }
        runCatching { storeScope.coroutineContext[Job]!!.cancelAndJoin() }.exceptionOrNull()?.let(errors::add)
        runCatching { FileSystem.SYSTEM.deleteRecursively(directory) }.exceptionOrNull()?.let(errors::add)
        errors.firstOrNull()?.let { first -> errors.drop(1).forEach(first::addSuppressed); throw first }
    }

    internal fun helper(): StoreSession {
        val arguments = listOf(file.toString()) + if (withoutInitializers) listOf("--no-initializers") else emptyList()
        val session = StoreSession(startDatastoreHelper(arguments))
        helpers += session
        session.event(0, "READY")
        return session
    }
}

internal expect fun datastoreVerboseLogging(): Boolean
