package top.kagg886.datastore.multiprocess.util

import java.nio.file.Files

internal actual object NativeLibrary {
    private val loaded by lazy {
        val name = System.mapLibraryName("datastore")
        val file = Files.createTempFile("datastore-", "-$name").toFile()
        file.deleteOnExit()
        NativeLibrary::class.java.getResourceAsStream("/$name")!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        System.load(file.absolutePath)
    }

    actual fun ensureNativeLibraryInited() = loaded
}
