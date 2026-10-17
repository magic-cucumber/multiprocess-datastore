package top.kagg886.datastore.multiprocess.util

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import top.kagg886.datastore.multiprocess.testhelper.TestState
import top.kagg886.datastore.multiprocess.testhelper.testStore

/** Real subprocesses, real filesystem and the public factory; follows AndroidX IPC scenarios. */
class MultiProcessDatastoreTest : DatastoreTestFixture() {
    @Test fun updatesAreVisibleInBothDirections() = runBlocking {
        withTimeout(30.seconds) {
            val remote = helper()
            assertEquals(TestState(), store.data.first())
            remote.text("remote")
            assertEquals(TestState(text = "remote"), store.data.first())
            store.updateData { it.copy(text = "host") }
            assertEquals(TestState(text = "host"), remote.read())
            remote.exit()
        }
    }

    @Test fun activeCollectorReceivesRepeatedRemoteCommits() = runBlocking {
        withTimeout(30.seconds) {
            val remote = helper()
            val observed = MutableStateFlow(TestState(text = "uninitialized"))
            val collector = launch { store.data.collect { observed.value = it } }
            try {
                observed.first { it == TestState() }
                repeat(5) { index ->
                    val expected = TestState(text = "remote$index")
                    remote.text(expected.text)
                    assertEquals(expected, observed.first { it == expected })
                }
            } finally { collector.cancelAndJoin() }
            remote.exit()
        }
    }

    @Test fun readsSeeCommittedValueWhileOtherProcessHoldsWriteLock() = runBlocking {
        withTimeout(30.seconds) {
            val writer = helper()
            val reader = helper()
            store.updateData { it.copy(text = "committed") }
            // Cold initialization may require an exclusive lock. Warm each instance before
            // testing reads of already committed data during another process's transaction.
            assertEquals("committed", writer.read().text)
            assertEquals("committed", reader.read().text)
            val held = writer.hold("pending")
            assertEquals("committed", store.data.first().text)
            assertEquals("committed", writer.read().text)
            assertEquals("committed", reader.read().text)
            writer.finish(held)
            assertEquals("pending", writer.value(held).text)
            assertEquals("pending", store.data.first().text)
            assertEquals("pending", reader.read().text)
            writer.exit(); reader.exit()
        }
    }

    @Test fun firstReadWaitsForInFlightTransaction() = runBlocking {
        withTimeout(30.seconds) {
            val remote = helper()
            val held = remote.hold("committed")
            val requested = CompletableDeferred<Unit>()
            val read = async(Dispatchers.Default) {
                requested.complete(Unit)
                store.data.first()
            }
            try {
                requested.await()
                assertNull(withTimeoutOrNull(150.milliseconds) { read.await() })
                remote.finish(held); remote.value(held)
                assertEquals(TestState(text = "committed"), read.await())
            } finally { read.cancelAndJoin() }
            remote.exit()
        }
    }

    @Test fun interleavedUpdatesPreserveBothChanges() = runBlocking {
        withTimeout(30.seconds) {
            val remote = helper()
            val held = remote.hold("remote")
            val requested = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val local = async(Dispatchers.Default) {
                requested.complete(Unit)
                store.updateData { entered.complete(Unit); it.copy(count = it.count + 1) }
            }
            try {
                requested.await()
                // A bounded contention check, matching the official interleaving tests.
                assertNull(withTimeoutOrNull(150.milliseconds) { entered.await() })
                assertFalse(local.isCompleted)
                remote.finish(held)
                remote.value(held)
                assertEquals(TestState(1, "remote"), local.await())
                assertEquals(TestState(1, "remote"), remote.read())
            } finally { local.cancelAndJoin() }
            remote.exit()
        }
    }

    private fun abortedRemoteTransaction(mode: String) = runBlocking {
        withTimeout(30.seconds) {
            val remote = helper()
            val held = remote.hold("mustNotCommit")
            val requested = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val local = async(Dispatchers.Default) {
                requested.complete(Unit)
                store.updateData { entered.complete(Unit); it.copy(count = 7) }
            }
            try {
                requested.await()
                assertNull(withTimeoutOrNull(150.milliseconds) { entered.await() })
                if (mode == "CANCEL") {
                    remote.event(remote.send("CANCEL", held.toString()), "DONE")
                    remote.event(held, "CANCELLED")
                } else {
                    remote.finish(held, "FAIL")
                    remote.event(held, "ERROR")
                }
                assertEquals(TestState(7), local.await())
                assertEquals(TestState(7), remote.read())
            } finally { local.cancelAndJoin() }
            remote.exit()
        }
    }

    @Test fun transformExceptionReleasesLock() = abortedRemoteTransaction("FAIL")
    @Test fun callerCancellationReleasesLock() = abortedRemoteTransaction("CANCEL")

    @Test fun cancellationWhileWaitingDoesNotAffectNextUpdate() = runBlocking {
        withTimeout(30.seconds) {
            val remote = helper()
            val held = remote.hold("remote")
            val entered = CompletableDeferred<Unit>()
            val waiting = launch(Dispatchers.Default) {
                store.updateData { entered.complete(Unit); it.copy(count = 100) }
            }
            assertNull(withTimeoutOrNull(150.milliseconds) { entered.await() })
            waiting.cancelAndJoin()
            remote.finish(held); remote.value(held)
            assertEquals(TestState(1, "remote"), store.updateData { it.copy(count = it.count + 1) })
            assertEquals(TestState(1, "remote"), remote.read())
            remote.exit()
        }
    }

    @Test fun concurrentUpdatesAcrossThreeProcessesDoNotLoseIncrements() = runBlocking {
        withTimeout(60.seconds) {
            val first = helper()
            val second = helper()
            store.data.first()
            val a = first.send("ADD", "20")
            val b = second.send("ADD", "20")
            repeat(20) { store.updateData { it.copy(count = it.count + 1) } }
            first.value(a); second.value(b)
            assertEquals(60, store.data.first().count)
            assertEquals(60, first.read().count)
            assertEquals(60, second.read().count)
            first.exit(); second.exit()
        }
    }

    @Test fun cancelledStoreCanBeReopenedWhileRemoteRemainsActive() = runBlocking {
        withTimeout(30.seconds) {
            val remote = helper()
            remote.text("before")
            assertEquals("before", store.data.first().text)
            storeScope.coroutineContext[Job]!!.cancelAndJoin()
            remote.text("after")
            storeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            store = testStore(file, storeScope)
            assertEquals("after", store.data.first().text)
            store.updateData { it.copy(count = 8) }
            assertEquals(TestState(8, "after"), remote.read())
            remote.exit()
        }
    }

    @Test fun gracefulHelperRestartReadsPersistedState() = runBlocking {
        withTimeout(30.seconds) {
            val first = helper()
            first.text("persisted"); first.exit()
            val second = helper()
            assertEquals(TestState(text = "persisted"), second.read())
            second.text("restarted")
            assertEquals("restarted", store.data.first().text)
            second.exit()
        }
    }
}
