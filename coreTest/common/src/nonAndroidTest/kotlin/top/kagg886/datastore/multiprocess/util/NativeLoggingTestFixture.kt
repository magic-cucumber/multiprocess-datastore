package top.kagg886.datastore.multiprocess.util

import kotlin.test.BeforeTest

/** Shared by JVM and iOS tests; Gradle --info or --debug enables the complete native trace. */
abstract class NativeLoggingTestFixture {
    @BeforeTest
    fun configureNativeTestLogging() {
        NativeLogger.setMinLevel(
            if (allowVerboseLog()) NativeLogger.Level.VERBOSE else NativeLogger.Level.INFO,
        )
    }
}

internal expect fun allowVerboseLog(): Boolean
