package top.kagg886.datastore.multiprocess.util

import kotlin.time.Duration
import okio.Path

internal actual fun platformLock(path: Path, shared: Boolean, wait: Duration): FileLock? {
    TODO("Not yet implemented")
}

internal actual fun platformTryLock(path: Path, shared: Boolean): FileLock? {
    TODO("Not yet implemented")
}
