@file:OptIn(top.kagg886.datastore.multiprocess.util.VisibleForTest::class)

package top.kagg886.datastore.multiprocess.util

import okio.Path
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/10/11 21:54
 * ================================================
 */
@VisibleForTest
public interface SharedCounter : AutoCloseable {

    public val closed: AtomicBoolean
    public fun getValue(): Int
    public fun incrementAndGetValue(): Int

    //do not call it directly
    public fun close0(): Unit
    public override fun close(): Unit {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) close0()
    }

    public companion object {
        public fun new(path: Path): SharedCounter =
            platformNewSharedCounter(path)
    }
}

internal expect fun platformNewSharedCounter(path: Path): SharedCounter
