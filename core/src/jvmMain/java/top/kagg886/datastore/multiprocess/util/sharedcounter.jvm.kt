package top.kagg886.datastore.multiprocess.util

import okio.Path
import kotlin.concurrent.atomics.AtomicBoolean

internal actual fun platformNewSharedCounter(path: Path): SharedCounter = SharedCounterImpl.create(path)


internal class SharedCounterImpl(private var ptr: Long) : SharedCounter {
    override val closed: AtomicBoolean = AtomicBoolean(false)

    // The instance monitor also protects the native pointer while close0 releases it.
    @Synchronized
    external override fun getValue(): Int
    @Synchronized
    external override fun incrementAndGetValue(): Int
    @Synchronized
    external override fun close0()

    companion object {
        init {
            NativeLibrary.ensureNativeLibraryInited()
        }

        fun create(path: Path): SharedCounter = when (val ptr = create0(path)) {
            in 1L..Long.MAX_VALUE -> SharedCounterImpl(ptr)
            else -> throw SharedCounterException((-ptr).toInt())
        }
        @JvmStatic
        external fun create0(path: Path): Long
    }
}
