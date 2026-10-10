package top.kagg886.datastore.multiprocess.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeLoggerTest {
    @Test
    fun defaultThresholdAndRegistrationLifecycle() {
        val messages = mutableListOf<String>()
        val receiver = NativeLogger { _, tag, message -> messages += "$tag:$message" }
        NativeLogger.register(receiver)
        try {
            assertFailsWith<IllegalStateException> { NativeLogger.register(receiver) }
            NativeLogger.log(NativeLogger.Level.DEBUG, "lock.cpp:10", "filtered")
            NativeLogger.log(NativeLogger.Level.INFO, "lock.cpp:11", "中文😀")
            assertEquals(listOf("lock.cpp:11:中文😀"), messages)
        } finally {
            NativeLogger.unregister(receiver)
        }
        NativeLogger.log(NativeLogger.Level.INFO, "lock.cpp:12", "after unregister")
        assertEquals(1, messages.size)
        assertFailsWith<IllegalStateException> { NativeLogger.unregister(receiver) }
    }

    @Test
    fun failingReceiverDoesNotInterruptLaterReceivers() {
        val failing = NativeLogger { _, _, _ -> error("sink failure") }
        var received = false
        val healthy = NativeLogger { _, _, _ -> received = true }
        NativeLogger.register(failing)
        NativeLogger.register(healthy)
        try {
            NativeLogger.log(NativeLogger.Level.ERROR, "test", "message")
            assertEquals(true, received)
        } finally {
            NativeLogger.unregister(failing)
            NativeLogger.unregister(healthy)
        }
    }
}
