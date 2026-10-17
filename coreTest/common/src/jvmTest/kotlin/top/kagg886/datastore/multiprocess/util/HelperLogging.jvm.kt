package top.kagg886.datastore.multiprocess.util

import java.time.Instant

internal actual fun testEpochMicros(): Long = Instant.now().let { it.epochSecond * 1_000_000 + it.nano / 1_000 }
