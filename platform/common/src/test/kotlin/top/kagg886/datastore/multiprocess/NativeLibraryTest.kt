package top.kagg886.datastore.multiprocess

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull

class NativeLibraryTest {
    @Test
    fun packagedNativeLibraryLoads() {
        val libraryName = System.mapLibraryName("datastore")
        val library = assertNotNull(javaClass.getResourceAsStream("/$libraryName"))
        val extracted = Files.createTempFile("datastore-", "-$libraryName").toFile()
        extracted.deleteOnExit()
        library.use { input -> extracted.outputStream().use { input.copyTo(it) } }
        System.load(extracted.absolutePath)
    }
}
