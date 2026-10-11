package top.kagg886.datastore.multiprocess.util

import okio.IOException

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/10/11 22:03
 * ================================================
 */

internal class SharedCounterException(@Suppress("unused") internal val errno: Int) : IOException(format(errno)) {
    companion object {
        fun format(errno: Int): String {
            val os = platform()
            val description = when {
                os.startsWith("Win") -> when (errno) {
                    2 -> "ERROR_FILE_NOT_FOUND: File not found"
                    3 -> "ERROR_PATH_NOT_FOUND: Path not found"
                    4 -> "ERROR_TOO_MANY_OPEN_FILES: Too many open files"
                    5 -> "ERROR_ACCESS_DENIED: Access denied"
                    6 -> "ERROR_INVALID_HANDLE: Invalid file handle"
                    8 -> "ERROR_NOT_ENOUGH_MEMORY: Insufficient memory"
                    14 -> "ERROR_OUTOFMEMORY: Insufficient memory"
                    19 -> "ERROR_WRITE_PROTECT: Write-protected storage"
                    29 -> "ERROR_WRITE_FAULT: Unable to write counter file"
                    32 -> "ERROR_SHARING_VIOLATION: File sharing conflict"
                    33 -> "ERROR_LOCK_VIOLATION: File initialization lock conflict"
                    50 -> "ERROR_NOT_SUPPORTED: Operation not supported"
                    87 -> "ERROR_INVALID_PARAMETER: Invalid parameter"
                    112 -> "ERROR_DISK_FULL: Insufficient storage"
                    1132 -> "ERROR_MAPPED_ALIGNMENT: Incorrect mapping alignment"
                    1450 -> "ERROR_NO_SYSTEM_RESOURCES: Insufficient system resources"
                    else -> "Unknown Windows error: $errno"
                }

                os.startsWith("Linux") -> when (errno) {
                    27 -> "EFBIG: Counter file exceeds size limit"
                    28 -> "ENOSPC: Insufficient storage"
                    36 -> "ENAMETOOLONG: File path is too long"
                    75 -> "EOVERFLOW: File size cannot be represented"
                    95 -> "ENOTSUP/EOPNOTSUPP: Operation not supported"
                    else -> formatUnix(errno) ?: "Unknown Linux error: $errno"
                }

                os.startsWith("Apple") -> when (errno) {
                    27 -> "EFBIG: Counter file exceeds size limit"
                    28 -> "ENOSPC: Insufficient storage"
                    45 -> "ENOTSUP: Operation not supported"
                    63 -> "ENAMETOOLONG: File path is too long"
                    84 -> "EOVERFLOW: File size cannot be represented"
                    else -> formatUnix(errno) ?: "Unknown Apple error: $errno"
                }

                else -> "Unknown platform error ($os)"
            }
            return "$description (errno=$errno)"
        }

        private fun formatUnix(errno: Int): String? = when (errno) {
            1 -> "EPERM: Operation not permitted"
            2 -> "ENOENT: File or path not found"
            4 -> "EINTR: Operation interrupted by a signal"
            5 -> "EIO: I/O error"
            9 -> "EBADF: Invalid file descriptor or closed counter"
            12 -> "ENOMEM: Insufficient memory"
            13 -> "EACCES: Permission denied"
            19 -> "ENODEV: File does not support memory mapping"
            20 -> "ENOTDIR: Path component is not a directory"
            21 -> "EISDIR: Path refers to a directory"
            22 -> "EINVAL: Invalid argument or unsupported file type"
            23 -> "ENFILE: System open file limit reached"
            24 -> "EMFILE: Process open file limit reached"
            30 -> "EROFS: Read-only file system"
            else -> null
        }
    }
}
