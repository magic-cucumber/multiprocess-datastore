@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package top.kagg886.datastore.multiprocess

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataMigrationInitializer
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreImpl
import androidx.datastore.core.Storage
import androidx.datastore.core.handlers.NoOpCorruptionHandler
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.jvm.JvmOverloads

public actual object MultiProcessDataStoreFactory {
    @JvmOverloads
    public actual fun <T> create(
        storage: Storage<T>,
        corruptionHandler: ReplaceFileCorruptionHandler<T>? ,
        migrations: List<DataMigration<T>>,
        scope: CoroutineScope,
    ): DataStore<T> = DataStoreImpl<T>(
        storage = storage,
        initTasksList = listOf(DataMigrationInitializer.getInitializer(migrations)),
        corruptionHandler = corruptionHandler ?: NoOpCorruptionHandler(),
        scope = scope,
    )

    @JvmOverloads
    public actual fun <T> create(
        serializer: OkioSerializer<T>,
        corruptionHandler: ReplaceFileCorruptionHandler<T>? ,
        migrations: List<DataMigration<T>>,
        scope: CoroutineScope,
        produceFile: () -> Path,
    ): DataStore<T> = DataStoreImpl<T>(
        storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer,
            { path, fs -> OkioInterProcessCoordinator(path,fs,scope) },
            produceFile,
        ),
        initTasksList = listOf(DataMigrationInitializer.getInitializer(migrations)),
        corruptionHandler = corruptionHandler ?: NoOpCorruptionHandler(),
        scope = scope,
    )
}
