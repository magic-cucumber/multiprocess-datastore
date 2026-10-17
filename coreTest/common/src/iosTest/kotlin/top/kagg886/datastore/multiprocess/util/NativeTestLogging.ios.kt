@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.datastore.multiprocess.util

import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun allowVerboseLog(): Boolean =
    (getenv("DATASTORE_TEST_LOG_LEVEL")?.toKString()?.toIntOrNull() ?: 3) <= 1
