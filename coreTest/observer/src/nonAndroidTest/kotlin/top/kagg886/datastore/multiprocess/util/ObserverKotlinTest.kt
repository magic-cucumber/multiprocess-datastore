package top.kagg886.datastore.multiprocess.util

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.*

/** Public Kotlin subscription/registry behavior, exercised through real OS events. */
class ObserverKotlinTest : ObserverTestFixture() {
    @Test fun sameFileSubscriptionsCloseIndependently(): Unit {
        val a = probe(); val b = probe(); val c = probe()
        val first = observe(a); observe(b); val third = observe(c)
        val h = helper()
        h.write("round-one")
        listOf(a, b, c).forEach { changed(it, "round-one") }
        first.close(); third.close()
        val ca = a.changes.load(); val cc = c.changes.load()
        h.write("round-two")
        changed(b, "round-two")
        unchanged(a, ca); unchanged(c, cc)
    }
    @Test fun interleavedFilesAndSubscriptionsAreIsolated(): Unit {
        val a = probe(); val b = probe(other); val a2 = probe()
        observe(a); observe(b); observe(a2)
        val h = helper()
        h.write("target-one")
        changed(a, "target-one"); changed(a2, "target-one"); unchanged(b, 0)
        h.write("other-one", "OTHER")
        changed(b, "other-one")
        // Unique contents distinguish this round from delayed callbacks in the previous round.
        h.write("target-two")
        changed(a, "target-two"); changed(a2, "target-two")
        assertEquals("other-one", b.content.load())
    }
    @Test fun equivalentOrdinaryPathsShareUsableSubscriptions(): Unit {
        val nested = directory / "nested"
        fs.createDirectory(nested)
        val a = probe(); val b = probe()
        val first = observe(a, nested / ".." / file.name)
        observe(b, directory / "." / file.name)
        val h = helper(); h.write("aliases-one")
        changed(a, "aliases-one"); changed(b, "aliases-one")
        first.close(); val count = a.changes.load()
        h.write("aliases-two"); changed(b, "aliases-two"); unchanged(a, count)
    }
    @Test fun differentDirectoriesRemainIndependent(): Unit {
        val nested = directory / "nested"; fs.createDirectory(nested)
        val target = nested / "target"; val sibling = nested / "other"
        seed(target, "nested"); seed(sibling, "sibling")
        val a = probe(); val b = probe(target)
        val first = observe(a); observe(b)
        val h = helper(); val h2 = helper(target, sibling)
        h.write("outer"); changed(a, "outer"); unchanged(b, 0)
        first.close(); val count = a.changes.load()
        h2.write("inner"); changed(b, "inner"); unchanged(a, count)
    }
    @Test fun lastCloseAllowsReobserveAndOldHandleCannotCloseNewSubscription(): Unit {
        val old = probe(); val handle = observe(old); val h = helper()
        h.write("old"); changed(old, "old")
        handle.close(); val count = old.changes.load()
        val next = probe(); observe(next)
        repeat(10) { handle.close() }
        h.write("new"); changed(next, "new"); unchanged(old, count)
    }
    @Test fun terminationFansOutAndOtherFileSurvives(): Unit {
        val a = probe(); val a2 = probe(); val b = probe(other)
        observe(a); observe(a2); observe(b)
        val h = helper(); h.command("DELETE TARGET")
        terminated(a); terminated(a2)
        val ca = a.changes.load(); val ca2 = a2.changes.load()
        h.write("other-survives", "OTHER"); changed(b, "other-survives")
        unchanged(a, ca, 1); unchanged(a2, ca2, 1)
    }
    @Test fun recreateRequiresNewSubscriptionAndOldHandlesStayHarmless(): Unit {
        val old = probe(); val handle = observe(old); val h = helper()
        h.command("DELETE TARGET"); terminated(old)
        val count = old.changes.load()
        h.write("recreated")
        val next = probe(); observe(next)
        repeat(5) { handle.close() }
        h.write("new-generation"); changed(next, "new-generation"); unchanged(old, count, 1)
    }
    @Test fun throwingChangedCallbackDoesNotBreakOtherSubscribersOrLaterEvents(): Unit {
        val bad = probe(changed = { error("Deliberate changed exception") }); val good = probe()
        observe(bad); observe(good)
        val h = helper()
        for (version in listOf("first-exception", "second-exception")) {
            h.write(version); changed(bad, version); changed(good, version)
        }
    }
    @Test fun throwingTerminationCallbackDoesNotBreakOtherSubscribers(): Unit {
        val bad = probe(terminated = { error("Deliberate termination exception") }); val good = probe()
        observe(bad); observe(good)
        helper().command("DELETE TARGET")
        terminated(bad); terminated(good)
    }
    @Test fun changedCallbackCanCloseItself(): Unit {
        val handle = AtomicReference<AutoCloseable?>(null)
        val a = probe(changed = { handle.load()!!.close() }); val witness = probe()
        handle.store(observe(a)); observe(witness)
        val h = helper(); h.write("self-close")
        changed(a, "self-close"); changed(witness, "self-close")
        val count = a.changes.load()
        h.write("after-self-close"); changed(witness, "after-self-close"); unchanged(a, count)
    }
    @Test fun changedCallbackCanCloseAnotherSubscription(): Unit {
        val victim = probe(); val victimHandle = observe(victim)
        val a = probe(changed = { victimHandle.close() }); observe(a)
        val h = helper(); h.write("close-other")
        changed(a, "close-other")
        val count = victim.changes.load()
        h.write("after-close-other"); changed(a, "after-close-other"); unchanged(victim, count)
    }
    @Test fun changedCallbackCanCloseAnotherFile(): Unit {
        val victim = probe(other); val handle = observe(victim)
        val a = probe(changed = { handle.close() }); observe(a)
        val h = helper(); h.write("close-file"); changed(a, "close-file")
        val count = victim.changes.load()
        h.write("closed-file-update", "OTHER")
        h.write("still-live"); changed(a, "still-live"); unchanged(victim, count)
    }
    @Test fun callbackCanAddSubscriptionForSubsequentEvents(): Unit {
        val added = probe(); val once = AtomicInt(0)
        val a = probe(changed = { if (once.compareAndSet(0, 1)) observe(added) })
        observe(a); val h = helper(); h.write("add-subscription"); changed(a, "add-subscription")
        h.write("after-add"); changed(a, "after-add"); changed(added, "after-add")
    }
    @Test fun concurrentFirstSubscriptionsAllReceiveUpdates(): Unit {
        val probes = List(12) { probe() }
        observerConcurrently(probes.map { p -> { observe(p) } })
        helper().write("concurrent-first")
        probes.forEach { changed(it, "concurrent-first") }
    }
    @Test fun concurrentSubscriptionsToMultipleFilesRemainUsable(): Unit {
        val probes = List(12) { probe(if (it % 2 == 0) file else other) }
        observerConcurrently(probes.map { p -> { observe(p) } })
        val h = helper(); h.write("concurrent-A"); h.write("concurrent-B", "OTHER")
        probes.forEach { changed(it, if (it.path == file) "concurrent-A" else "concurrent-B") }
    }
    @Test fun concurrentCloseIsIdempotentAndDoesNotAffectOtherSubscribers(): Unit {
        val a = probe(); val b = probe(); val handle = observe(a); observe(b)
        observerConcurrently(List(12) { { handle.close() } })
        val count = a.changes.load()
        helper().write("after-concurrent-close")
        changed(b, "after-concurrent-close"); unchanged(a, count)
    }
    @Test fun writesRacingWithSubscribeAndCloseDoNotBreakReobserve(): Unit {
        val h = helper()
        val probes = List(8) { probe() }
        observerConcurrently(probes.map { p -> { repeat(20) { observe(p).close() } } } + listOf({
            h.command("BURST TARGET 200")
        }))
        val counts = probes.map { it.changes.load() }
        val next = probe(); observe(next)
        h.write("after-race"); changed(next, "after-race")
        probes.forEachIndexed { i, p -> unchanged(p, counts[i]) }
    }
    @Test fun closeWaitsForRunningCallbackAndSuppressesLaterCallbacks(): Unit {
        val entered = AtomicInt(0); val release = AtomicInt(0)
        val closeStarted = AtomicInt(0); val closeReturned = AtomicInt(0)
        val p = probe(changed = {
            entered.store(1)
            await("Callback was never released") { release.load() == 1 }
        })
        val handle = observe(p); val h = helper()
        try {
            h.write("blocked-callback")
            await("Callback never entered") { entered.load() == 1 }
            observerConcurrently(listOf({
                closeStarted.store(1)
                handle.close()
                closeReturned.store(1)
            }, {
                try {
                    await("Close did not start") { closeStarted.load() == 1 }
                    observerPause(100)
                    assertEquals(0, closeReturned.load(), "close returned while callback was still running")
                } finally { release.store(1) }
            }))
        } finally { release.store(1) }
        assertEquals(1, closeReturned.load())
        val count = p.changes.load()
        val witness = probe(); observe(witness)
        h.write("after-blocked-close"); changed(witness, "after-blocked-close"); unchanged(p, count)
    }
    @Test fun concurrentlyClosingAllSubscriptionsAllowsFreshRegistration(): Unit {
        val probes = List(12) { probe() }
        val handles = probes.map { observe(it) }
        observerConcurrently(handles.map { handle -> { handle.close() } })
        val counts = probes.map { it.changes.load() }
        val fresh = probe(); observe(fresh)
        val h = helper(); h.write("after-all-concurrent-close"); changed(fresh, "after-all-concurrent-close")
        // One shared observation window avoids multiplying test duration by subscriber count.
        observerPause(300)
        probes.forEachIndexed { index, p ->
            assertEquals(counts[index], p.changes.load(), p.describe())
            assertEquals(0, p.terminations.load(), p.describe())
        }
    }
    @Test fun invalidTargetsDoNotBreakLaterRegistration(): Unit {
        assertFails { FileChangeListener.observe(directory / "absent", probe()) }
        assertFailsWith<IllegalArgumentException> { FileChangeListener.observe(directory, probe(directory)) }
        val p = probe(); observe(p)
        helper().write("valid-after-errors"); changed(p, "valid-after-errors")
    }
}
