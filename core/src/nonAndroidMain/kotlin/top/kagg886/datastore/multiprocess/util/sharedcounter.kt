package top.kagg886.datastore.multiprocess.util

import okio.Path
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/10/11 21:54
 * ================================================
 */
internal interface SharedCounter : AutoCloseable {

    val closed: AtomicBoolean
    fun getValue(): Int
    fun incrementAndGetValue(): Int

    //do not call it directly
    fun close0()
    override fun close() {
        if (closed.compareAndSet(expectedValue = false, newValue = true)) close0()
    }

    companion object {
        fun new(path: Path): SharedCounter =
            platformNewSharedCounter(path)
    }
}

internal expect fun platformNewSharedCounter(path: Path): SharedCounter
