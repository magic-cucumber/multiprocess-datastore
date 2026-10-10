package top.kagg886.datastore.multiprocess.util

internal actual fun platformSetNativeLoggerMinLevel(level: Int) {
    NativeLibrary.ensureNativeLibraryInited()
    NativeLoggerBridge.setMinLevel(level)
}

// Keep native library loading outside this object: JNI_OnLoad resolves this class.
internal object NativeLoggerBridge {
    @JvmStatic
    private fun onNativeLoggerPostedCallback(level: Int, tag: String, message: String) {
        NativeLogger.log(NativeLogger.Level.from(level), tag, message)
    }

    @JvmStatic
    external fun setMinLevel(level: Int)
}
