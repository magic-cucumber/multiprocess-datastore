package top.kagg886.datastore.multiprocess

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.Storage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import kotlinx.coroutines.CoroutineScope
import okio.Path
import okio.buffer
import okio.sink
import okio.source
import java.io.InputStream
import java.io.OutputStream
import androidx.datastore.core.MultiProcessDataStoreFactory as InternalMultiProcessDataStoreFactory

public actual object MultiProcessDataStoreFactory {
    @JvmOverloads
    public actual fun <T> create(
        storage: Storage<T>,
        corruptionHandler: ReplaceFileCorruptionHandler<T>?,
        migrations: List<DataMigration<T>>,
        scope: CoroutineScope
    ): DataStore<T> =
        InternalMultiProcessDataStoreFactory.create(storage, corruptionHandler, migrations, scope)

    @JvmOverloads
    public actual fun <T> create(
        serializer: OkioSerializer<T>,
        corruptionHandler: ReplaceFileCorruptionHandler<T>?,
        migrations: List<DataMigration<T>>,
        scope: CoroutineScope,
        produceFile: () -> Path
    ): DataStore<T> = InternalMultiProcessDataStoreFactory.create(
        serializer = object : Serializer<T> {
            override val defaultValue: T = serializer.defaultValue
            override suspend fun readFrom(input: InputStream): T = serializer.readFrom(input.source().buffer())
            override suspend fun writeTo(t: T, output: OutputStream) = serializer.writeTo(t, output.sink().buffer())
        },
        corruptionHandler = corruptionHandler,
        migrations = migrations,
        scope = scope,
        produceFile = { produceFile().toFile() }
    )
}
