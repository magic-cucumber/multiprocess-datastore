package top.kagg886.datastore.multiprocess.util

import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.*

/** Real native event delivery through the production JVM/JNI or iOS implementation. */
class ObserverNativeTest : ObserverTestFixture() {
    private fun mutation(command: String, expected: String) {
        val p = probe(); observe(p)
        helper().command(command)
        changed(p, expected)
    }
    @Test fun externalAppendNotifies(): Unit = mutation("APPEND TARGET ${"-appended".hex()}", "initial-appended")
    @Test fun externalTruncateAndWriteNotifies(): Unit = mutation("WRITE TARGET ${"x".hex()}", "x")
    @Test fun externalEmptyWriteNotifies(): Unit = mutation("WRITE TARGET -", "")
    @Test fun externalEqualLengthOverwriteNotifies(): Unit = mutation("PATCH TARGET 0 ${"changed".hex()}", "changed")
    @Test fun externalExtensionNotifies(): Unit = mutation("RESIZE TARGET 10", "initial\u0000\u0000\u0000")
    @Test fun externalShorteningNotifies(): Unit = mutation("RESIZE TARGET 3", "ini")
    @Test fun metadataOnlyChangeNotifies(): Unit {
        val p = probe(); observe(p)
        helper().command("SET_MTIME TARGET 946684800000")
        changed(p, "initial")
        assertEquals(946684800000L, fs.metadata(file).lastModifiedAtMillis)
    }
    @Test fun unrelatedSiblingWritesAreFiltered(): Unit {
        val p = probe(); observe(p)
        val h = helper(); h.write("sibling-update", "OTHER")
        unchanged(p, 0)
        h.write("target-update"); changed(p, "target-update")
    }
    @Test fun equalLengthAtomicReplacementKeepsWatchingNewFile(): Unit {
        val p = probe(); observe(p)
        val h = helper(); h.command("REPLACE TARGET ${"replace".hex()}")
        changed(p, "replace")
        h.command("APPEND TARGET ${"-new-file".hex()}")
        changed(p, "replace-new-file")
    }
    @Test fun repeatedReplacementAndAppendRebindsEveryTime(): Unit {
        val p = probe(); observe(p); val h = helper()
        repeat(20) { round ->
            val version = "replacement-$round"
            h.command("REPLACE TARGET ${version.hex()}"); changed(p, version)
            h.command("APPEND TARGET ${"-append".hex()}"); changed(p, "$version-append")
        }
    }
    @Test fun burstMayCoalesceButFinalStateIsObserved(): Unit {
        val p = probe(); observe(p)
        helper().command("BURST TARGET 500")
        changed(p, "version:500")
    }
    @Test fun deletionTerminatesExactlyOnce(): Unit {
        val p = probe(); val handle = observe(p); val h = helper()
        h.command("DELETE TARGET"); terminated(p)
        assertFalse(fs.exists(file))
        val count = p.changes.load()
        repeat(5) { handle.close() }
        h.write("recreated-but-not-observed")
        unchanged(p, count, 1)
    }
    @Test fun movingTargetAwayTerminates(): Unit {
        val p = probe(); observe(p)
        helper().command("MOVE_OUT TARGET"); terminated(p)
        assertFalse(fs.exists(file))
        assertEquals("initial", text(directory / (file.name + ".moved")))
        unchanged(p, p.changes.load(), 1)
    }
    @Test fun explicitCloseSuppressesChangesAndTermination(): Unit {
        val p = probe(); val handle = observe(p); val h = helper()
        handle.close(); val count = p.changes.load()
        h.write("after-close"); h.command("DELETE TARGET")
        // A second real listener proves that the platform's event service still progresses.
        val witness = probe(other); observe(witness)
        h.write("witness", "OTHER"); changed(witness, "witness")
        unchanged(p, count)
    }
    @Test fun lastSubscriptionCanCloseInsideCallback(): Unit {
        val handle = AtomicReference<AutoCloseable?>(null)
        val p = probe(changed = { handle.load()!!.close() })
        handle.store(observe(p))
        val h = helper(); h.write("last-self-close"); changed(p, "last-self-close")
        val count = p.changes.load()
        val next = probe(); observe(next)
        h.write("after-last-close"); changed(next, "after-last-close"); unchanged(p, count)
    }
    @Test fun normalWriterExitDoesNotTerminateObserver(): Unit {
        val p = probe(); observe(p); val h = helper()
        h.write("before-normal-exit"); changed(p, "before-normal-exit"); h.close()
        helper().write("after-normal-exit"); changed(p, "after-normal-exit")
    }
    @Test fun writerEofDoesNotTerminateObserver(): Unit {
        val p = probe(); observe(p); val h = helper()
        h.write("before-eof"); changed(p, "before-eof")
        h.process.closeStdin(); h.process.event("CLOSED"); assertEquals(0, h.process.awaitExit())
        helper().write("after-eof"); changed(p, "after-eof")
    }
    @Test fun killedWriterDoesNotTerminateObserver(): Unit {
        val p = probe(); observe(p); val h = helper()
        h.write("before-kill"); changed(p, "before-kill")
        h.process.terminate(); assertNotEquals(0, h.process.awaitExit())
        helper().write("after-kill"); changed(p, "after-kill")
    }
    @Test fun repeatedOpenWriteCloseKeepsNativeServiceUsable(): Unit {
        val h = helper()
        repeat(30) { round ->
            val p = probe(); val handle = observe(p)
            h.write("lifecycle-$round"); changed(p, "lifecycle-$round")
            handle.close()
            assertEquals(0, p.terminations.load())
        }
        val final = probe(); observe(final)
        h.write("after-all-cycles"); changed(final, "after-all-cycles")
    }
    @Test fun repeatedDeletionRecreationKeepsNativeServiceUsable(): Unit {
        val h = helper()
        repeat(10) { round ->
            val p = probe(); val handle = observe(p)
            h.write("generation-$round"); changed(p, "generation-$round")
            h.command("DELETE TARGET"); terminated(p)
            h.write("seed-next-$round"); handle.close()
        }
        val next = probe(); observe(next)
        h.write("last-generation"); changed(next, "last-generation")
    }
}
