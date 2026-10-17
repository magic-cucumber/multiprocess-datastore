@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package top.kagg886.datastore.multiprocess.testhelper

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreImpl
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineScope
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import top.kagg886.datastore.multiprocess.MultiProcessDataStoreFactory
import top.kagg886.datastore.multiprocess.OkioInterProcessCoordinator

data class TestState(val count: Int = 0, val text: String = "empty", val flag: Boolean = false)

object TestSerializer : OkioSerializer<TestState> {
    override val defaultValue = TestState()
    override suspend fun readFrom(source: BufferedSource): TestState {
        val fields = source.readUtf8().split('\n')
        val count = fields.firstOrNull()?.toIntOrNull()
        val flag = fields.getOrNull(2)?.toBooleanStrictOrNull()
        if (fields.size != 3 || count == null || flag == null) throw CorruptionException("Invalid test record")
        return TestState(count, fields[1], flag)
    }
    override suspend fun writeTo(t: TestState, sink: BufferedSink) {
        sink.writeUtf8("${t.count}\n${t.text}\n${t.flag}")
    }
}

fun testStore(path: Path, scope: CoroutineScope, withoutInitializers: Boolean = false): DataStore<TestState> =
    if (withoutInitializers) {
        // Match AndroidX's IPC tests: no migration initializer, even a no-op one.
        // Keep the public return type so internal DataStoreImpl never escapes this file.
        DataStoreImpl(
            storage = OkioStorage(
                fileSystem = FileSystem.SYSTEM,
                serializer = TestSerializer,
                coordinatorProducer = { file, fs -> OkioInterProcessCoordinator(file, fs, scope) },
                producePath = { path },
            ),
            // initTasksList defaults to emptyList(); do not install a migration task.
            scope = scope,
        )
    } else {
        MultiProcessDataStoreFactory.create(
            serializer = TestSerializer,
            scope = scope,
            produceFile = { path },
        )
    }
