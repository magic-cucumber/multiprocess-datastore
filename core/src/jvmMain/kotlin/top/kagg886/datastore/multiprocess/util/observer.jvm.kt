package top.kagg886.datastore.multiprocess.util

import okio.Path

internal actual object InternalDictionaryObserver {
    init {
        NativeLibrary.ensureNativeLibraryInited()
    }

    actual fun observe(path: Path, block: DictionaryObserverListener): AutoCloseable {
        return JvmDirectoryObservation(path, block).also { it.start() }
    }

    external fun observe0(path: String, block: InternalDictionaryObserverJNI): Long
    external fun stopObserve0(ptr: Long)
}


internal interface InternalDictionaryObserverJNI {
    // Each String is the full path of a direct child; native preserves raw rename notifications.
    fun handle(
        create: Array<String>,
        remove: Array<String>,
        modified: Array<String>,
        renamedOld: Array<String>,
        renameNew: Array<String>,
        overflow: Boolean,
    )

    fun terminate(cause: Int)
}
