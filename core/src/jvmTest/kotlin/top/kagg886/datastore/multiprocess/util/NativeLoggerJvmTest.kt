package top.kagg886.datastore.multiprocess.util

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeLoggerJvmTest {
    @Test
    fun jvmCallbackPreservesUnicodeAndFiltersLevels() {
        val messages = mutableListOf<String>()
        val receiver = NativeLogger { _, tag, message -> messages += "$tag:$message" }
        NativeLogger.register(receiver)
        try {
            val callback = NativeLoggerBridge::class.java.getDeclaredMethod(
                "onNativeLoggerPostedCallback", Int::class.javaPrimitiveType,
                String::class.java, String::class.java
            ).apply { isAccessible = true }
            callback.invoke(null, NativeLogger.Level.DEBUG.code, "native.cpp:1", "filtered")
            callback.invoke(null, NativeLogger.Level.ERROR.code, "native.cpp:2", "中文😀")
            assertEquals(listOf("native.cpp:2:中文😀"), messages)
        } finally {
            NativeLogger.unregister(receiver)
        }
    }
}
