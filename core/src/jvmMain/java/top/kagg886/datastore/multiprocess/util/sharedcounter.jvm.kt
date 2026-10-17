package top.kagg886.datastore.multiprocess.util

import top.kagg886.datastore.multiprocess.util.NativeLogger.Companion.log
import top.kagg886.datastore.multiprocess.util.NativeLogger.Level.DEBUG

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

        fun create(path: Path): SharedCounter {
            log(DEBUG, "SharedCounter.jvm", "Create requested path=$path")
            return when (val ptr = create0(path)) {
                in 1L..Long.MAX_VALUE -> {
                    log(DEBUG, "SharedCounter.jvm", "Install wrapper path=$path ptr=${ptr.toString(16)}")
                    SharedCounterImpl(ptr)
                }
                else -> {
                    log(DEBUG, "SharedCounter.jvm", "Translate native result path=$path result=$ptr -> SharedCounterException")
                    throw SharedCounterException((-ptr).toInt())
                }
            }
        }
        @JvmStatic
        external fun create0(path: Path): Long
    }
}
