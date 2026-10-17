package top.kagg886.datastore.multiprocess.util

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import top.kagg886.datastore.multiprocess.testhelper.TestState

/** Matches AndroidX's OKIO IPC cases, including its empty initialization-task list. */
class AndroidxOkioDatastoreTest : DatastoreTestFixture() {
    override val withoutInitializers: Boolean = true

    // AndroidX: testConcurrentReadUpdate_okio
    @Test fun coldThirdProcessReadsCommittedValueDuringWrite() = runBlocking {
        withTimeout(30.seconds) {
            val writer = helper()
            val reader = helper()
            store.updateData { it.copy(text = "hostData") }

            // Neither helper has been warmed by GET. HOLD initializes only the writer;
            // the third process performs its first read while the writer holds the lock.
            val held = writer.hold("remoteValue")
            val old = TestState(text = "hostData")
            assertEquals(old, store.data.first())
            assertEquals(old, writer.read())
            assertEquals(old, reader.read())

            writer.finish(held)
            val committed = TestState(text = "remoteValue")
            assertEquals(committed, writer.value(held))
            assertEquals(committed, store.data.first())
            assertEquals(committed, writer.read())
            assertEquals(committed, reader.read())
            writer.exit(); reader.exit()
        }
    }

    // AndroidX: testInterleavedUpdateDataWithLocalRead_okio
    @Test fun interleavedUpdatesWithLocalRead() = runBlocking {
        withTimeout(30.seconds) {
            val remote = helper()
            assertEquals(TestState(), store.data.first())

            // Remote commits count=1, then waits before its second update.
            val action = remote.send("INTERLEAVED")
            assertEquals(TestState(count = 1), remote.event(action, "UPDATED").value)
            val localStarted = CompletableDeferred<Unit>()
            val finishLocal = CompletableDeferred<Unit>()
            val local = async(Dispatchers.Default) {
                store.updateData {
                    assertEquals(TestState(count = 1), it)
                    localStarted.complete(Unit)
                    finishLocal.await()
                    // Construct a fresh state, resetting count as AndroidX does.
                    TestState(text = "hostValue")
                }
            }
            try {
                localStarted.await()
                // Local cache initially contained count=0. A read during this local
                // transform must return the remote commit, not stale or pending data.
                assertEquals(TestState(count = 1), store.data.first())

                remote.finish(action)
                remote.event(action, "WILL_WRITE")
                // Match AndroidX's bounded window for the remote write to contend.
                delay(200.milliseconds)
                assertEquals(TestState(count = 1), remote.read())
                assertFalse(local.isCompleted)

                finishLocal.complete(Unit)
                assertEquals(TestState(text = "hostValue"), local.await())
                // The second remote transform must use the local commit as its base.
                assertEquals(TestState(text = "hostValue"), remote.event(action, "LOCKED").value)
                val expected = TestState(text = "hostValue", flag = true)
                assertEquals(expected, remote.value(action))
                assertEquals(expected, store.data.first())
                assertEquals(expected, remote.read())
            } finally {
                local.cancelAndJoin()
            }
            remote.exit()
        }
    }
}
