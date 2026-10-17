package top.kagg886.datastore.multiprocess.util

import kotlin.test.*

class ObserverHelperTest : ObserverTestFixture() {
    @Test fun readyPingAndCleanClose(): Unit {
        val h = helper()
        h.command("PING", "PONG")
        h.close()
        assertEquals("initial", text())
    }
    @Test fun rejectsInvalidArguments(): Unit {
        for (arguments in listOf(emptyList(), listOf("--unknown", "x"),
            listOf("--path", file.toString(), "--other", file.toString()),
            listOf("--path", directory.toString(), "--other", other.toString()))) {
            val h = rawHelper(arguments)
            h.event("ERROR"); assertEquals(2, h.awaitExit())
            assertEquals("initial", text())
        }
    }
    @Test fun rejectsInvalidCommandsBeforeMutation(): Unit {
        for (command in listOf("1 UNKNOWN", "0 WRITE TARGET 78", "1 WRITE TARGET gg",
            "1 WRITE TARGET 1", "1 WRITE TARGET 78 extra", "1 RESIZE TARGET -1",
            "1 RESIZE TARGET 1048577", "1 PATCH TARGET 7 78", "1 BURST TARGET 10001",
            "1 BURST TARGET 0", "1 DELETE UNKNOWN", "1 SET_MTIME TARGET 999999999999999999999")) {
            val h = helper()
            h.process.send(command)
            val event = ObserverEvent(h.process.nextLine())
            assertEquals("ERROR", event.name, event.raw)
            assertEquals(2, h.process.awaitExit(), command)
            assertEquals("initial", text(), command)
        }
    }
    @Test fun rejectsRepeatedRequestId(): Unit {
        val h = helper(); h.command("PING", "PONG")
        h.process.send("1 WRITE TARGET 78")
        h.process.event("ERROR", 1); assertEquals(2, h.process.awaitExit())
        assertEquals("initial", text())
    }
    @Test fun reportsRealFilesystemError(): Unit {
        val h = helper(); h.command("DELETE TARGET")
        val event = h.command("DELETE TARGET", "ERROR")
        assertTrue(event.number("code") != 0L)
        assertEquals(4, h.process.awaitExit())
    }
    @Test fun doneReportsFileSizeAndDeletion(): Unit {
        val h = helper()
        assertEquals(3L, h.command("WRITE TARGET 000102").number("size"))
        assertContentEquals(byteArrayOf(0, 1, 2), fs.read(file) { readByteArray() })
        assertNull(h.command("DELETE TARGET").optionalNumber("size"))
        h.close()
    }
}
