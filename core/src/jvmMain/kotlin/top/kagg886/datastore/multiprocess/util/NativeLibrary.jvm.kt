package top.kagg886.datastore.multiprocess.util

import top.kagg886.datastore.multiprocess.util.NativeLogger.Companion.log
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.DEBUG
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.INFO
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.ERROR

import java.nio.file.Files

@Suppress("UnsafeDynamicallyLoadedCode")
internal actual object NativeLibrary {
    private val loaded by lazy {
        val name = System.mapLibraryName("datastore")
        var stage = "locate resource"
        try {
            log(DEBUG, "NativeLibrary.jvm", "Loading resource=/$name")
            stage = "create temporary file"
            val file = Files.createTempFile("datastore-", "-$name").toFile()
            file.deleteOnExit()
            stage = "extract resource"
            NativeLibrary::class.java.getResourceAsStream("/$name")!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            log(DEBUG, "NativeLibrary.jvm", "Extracted library path=${file.absolutePath} bytes=${file.length()}")
            stage = "System.load"
            System.load(file.absolutePath)
            log(INFO, "NativeLibrary.jvm", "Loaded library path=${file.absolutePath}")
        } catch (error: Throwable) {
            log(ERROR, "NativeLibrary.jvm", "Library load failed resource=/$name stage=$stage: $error")
            throw error
        }
    }

    actual fun ensureNativeLibraryInited() = loaded
}
