package top.kagg886.datastore.multiprocess.util

import kotlin.test.BeforeTest

/** Shared by JVM and iOS tests; Gradle --debug enables the complete native trace. */
abstract class NativeLoggingTestFixture {
    @BeforeTest
    fun configureNativeTestLogging() {
        NativeLogger.setMinLevel(
            if (nativeTestDebugLoggingEnabled()) NativeLogger.Level.VERBOSE else NativeLogger.Level.INFO,
        )
    }
}

internal expect fun nativeTestDebugLoggingEnabled(): Boolean
