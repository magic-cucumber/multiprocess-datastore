@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.datastore.multiprocess.util

import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun nativeTestDebugLoggingEnabled(): Boolean =
    getenv("DATASTORE_TEST_DEBUG")?.toKString() == "true"
