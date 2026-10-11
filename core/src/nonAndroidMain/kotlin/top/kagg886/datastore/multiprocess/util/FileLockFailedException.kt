package top.kagg886.datastore.multiprocess.util

import okio.IOException

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/10/10 23:33
 * ================================================
 */

internal class FileLockFailedException(@Suppress("unused") internal val errno: Int) : IOException(format(errno)) {
    companion object {
        fun format(errno: Int): String {
            val os = platform()
            val description = when {
                os.startsWith("Win") -> when (errno) {
                    1 -> "ERROR_INVALID_FUNCTION: Incorrect function"
                    2 -> "ERROR_FILE_NOT_FOUND: File not found"
                    3 -> "ERROR_PATH_NOT_FOUND: Path not found"
                    4 -> "ERROR_TOO_MANY_OPEN_FILES: Too many open files"
                    5 -> "ERROR_ACCESS_DENIED: Access denied"
                    6 -> "ERROR_INVALID_HANDLE: Invalid file handle"
                    8 -> "ERROR_NOT_ENOUGH_MEMORY: Insufficient memory"
                    32 -> "ERROR_SHARING_VIOLATION: File sharing conflict"
                    33 -> "ERROR_LOCK_VIOLATION: File lock conflict"
                    50 -> "ERROR_NOT_SUPPORTED: Operation not supported"
                    87 -> "ERROR_INVALID_PARAMETER: Invalid parameter"
                    167 -> "ERROR_LOCK_FAILED: Unable to lock file region"
                    995 -> "ERROR_OPERATION_ABORTED: Operation aborted"
                    997 -> "ERROR_IO_PENDING: Asynchronous operation pending"
                    else -> "Unknown Windows error: $errno"
                }

                os.startsWith("Linux") -> when (errno) {
                    11 -> "EAGAIN/EWOULDBLOCK: File lock conflict"
                    37 -> "ENOLCK: Insufficient resources for lock records"
                    95 -> "ENOTSUP/EOPNOTSUPP: Operation not supported"
                    else -> formatUnix(errno) ?: "Unknown Linux error: $errno"
                }

                os.startsWith("Apple") -> when (errno) {
                    35 -> "EAGAIN/EWOULDBLOCK: File lock conflict"
                    45 -> "ENOTSUP: Operation not supported"
                    77 -> "ENOLCK: Insufficient resources for lock records"
                    102 -> "EOPNOTSUPP: Operation not supported on socket"
                    else -> formatUnix(errno) ?: "Unknown macOS error: $errno"
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
            9 -> "EBADF: Invalid file descriptor"
            12 -> "ENOMEM: Insufficient memory"
            13 -> "EACCES: Permission denied"
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

