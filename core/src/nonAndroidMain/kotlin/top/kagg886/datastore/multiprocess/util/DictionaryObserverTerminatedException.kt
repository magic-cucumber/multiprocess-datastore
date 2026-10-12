package top.kagg886.datastore.multiprocess.util

import androidx.annotation.VisibleForTesting
import okio.IOException
import okio.Path

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/10/10 23:33
 * ================================================
 */
@VisibleForTesting
public class DictionaryObserverTerminatedException(
    public val errno: Int,
    public val path: Path? = null,
    cause: Throwable? = null,
) : IOException(format(errno) + (path?.let { " (directory=$it)" } ?: ""), cause) {
    public val platformName: String = platform()
    public val reason: DictionaryObserverTerminationReason = classify(errno)

    public companion object {
        internal fun internal(path: Path, cause: Throwable): DictionaryObserverTerminatedException =
            DictionaryObserverTerminatedException(-1, path, cause)

        private fun classify(errno: Int): DictionaryObserverTerminationReason {
            if (errno == -1) return DictionaryObserverTerminationReason.INTERNAL_ERROR
            return if (platform().startsWith("Win")) when (errno) {
                2, 3, 6, 267 -> DictionaryObserverTerminationReason.DIRECTORY_UNAVAILABLE
                5 -> DictionaryObserverTerminationReason.PERMISSION_DENIED
                4, 8, 14, 1450 -> DictionaryObserverTerminationReason.RESOURCE_EXHAUSTED
                else -> DictionaryObserverTerminationReason.IO_ERROR
            } else when (errno) {
                2, 9, 20 -> DictionaryObserverTerminationReason.DIRECTORY_UNAVAILABLE
                1, 13 -> DictionaryObserverTerminationReason.PERMISSION_DENIED
                12, 23, 24, 28 -> DictionaryObserverTerminationReason.RESOURCE_EXHAUSTED
                else -> DictionaryObserverTerminationReason.IO_ERROR
            }
        }

        public fun format(errno: Int): String {
            val os = platform()
            val description = when {
                os.startsWith("Win") -> when (errno) {
                    2, 3, 267 -> "Observed directory is unavailable"
                    5 -> "Permission denied"
                    6 -> "Invalid directory handle"
                    4, 8, 14, 1450 -> "Observer resources exhausted"
                    -1 -> "Directory observer internal failure"
                    else -> "Unknown Windows error: $errno"
                }

                os.startsWith("Linux") -> when (errno) {
                    2, 9, 20 -> "Observed directory is unavailable"
                    1, 13 -> "Permission denied"
                    12, 23, 24, 28 -> "Observer resources exhausted"
                    -1 -> "Directory observer internal failure"
                    else -> "Unknown Linux error: $errno"
                }

                os.startsWith("Apple") -> when (errno) {
                    2, 9, 20 -> "Observed directory is unavailable"
                    1, 13 -> "Permission denied"
                    12, 23, 24, 28 -> "Observer resources exhausted"
                    -1 -> "Directory observer internal failure"
                    else ->"Unknown macOS error: $errno"
                }

                else -> "Unknown platform error ($os)"
            }
            return "$description (errno=$errno)"
        }
    }
}

@VisibleForTesting
public enum class DictionaryObserverTerminationReason {
    DIRECTORY_UNAVAILABLE, PERMISSION_DENIED, RESOURCE_EXHAUSTED, IO_ERROR, INTERNAL_ERROR,
}

