package top.kagg886.datastore.multiprocess.util

import okio.FileSystem
import okio.HashingSource
import okio.IOException
import okio.Path
import okio.SYSTEM
import okio.buffer

/** Owned exclusively by the platform observer's serial processing thread. */
internal class DirectorySnapshot(private val directory: Path) {
    private data class FileState(val digest: okio.ByteString, val size: Long?, val modifiedAt: Long?)
    private val states = mutableMapOf<Path, FileState>()

    fun initialize() {
        states.clear()
        states.putAll(scan())
    }

    fun diff(candidates: Set<Path>?, fullScan: Boolean): List<DictionaryChangeEvent> {
        val next = if (fullScan) scan() else states.toMutableMap().also { result ->
            candidates.orEmpty().filter { it.parent == directory }.forEach { path ->
                val state = read(path)
                if (state == null) result.remove(path) else result[path] = state
            }
        }
        val events = (states.keys + next.keys).sortedBy { it.toString() }.mapNotNull { path ->
            val before = states[path]
            val after = next[path]
            val type = when {
                before == null && after != null -> DictionaryChangeEventType.CREATE
                before != null && after == null -> DictionaryChangeEventType.REMOVE
                before?.digest != after?.digest -> DictionaryChangeEventType.MODIFIED
                else -> null
            }
            type?.let { DictionaryChangeEvent(path, it) }
        }
        states.clear()
        states.putAll(next)
        return events
    }

    private fun scan(): MutableMap<Path, FileState> {
        val result = mutableMapOf<Path, FileState>()
        for (path in FileSystem.SYSTEM.list(directory)) read(path)?.let { result[path] = it }
        return result
    }

    private fun read(path: Path): FileState? {
        // A failed/unstable read aborts this transaction; callers retry without losing the baseline.
        repeat(3) {
            val before = FileSystem.SYSTEM.metadataOrNull(path) ?: return null
            if (!before.isRegularFile || before.symlinkTarget != null) return null
            var bytesRead = 0L
            val digest = try {
                val hashing = HashingSource.sha256(FileSystem.SYSTEM.source(path))
                hashing.buffer().use { source ->
                    val buffer = okio.Buffer()
                    while (true) {
                        val count = source.read(buffer, 8192)
                        if (count == -1L) break
                        bytesRead += count
                        buffer.clear()
                    }
                    hashing.hash
                }
            } catch (error: IOException) {
                if (FileSystem.SYSTEM.metadataOrNull(path) == null) return null
                if (it == 2) throw error
                return@repeat
            }
            val after = FileSystem.SYSTEM.metadataOrNull(path) ?: return null
            // FileMetadata uses reference equality. Access time may also change merely by hashing.
            if (after.isRegularFile && after.symlinkTarget == null && before.size == after.size &&
                before.lastModifiedAtMillis == after.lastModifiedAtMillis &&
                before.createdAtMillis == after.createdAtMillis &&
                (after.size == null || bytesRead == after.size)) {
                return FileState(digest, after.size, after.lastModifiedAtMillis)
            }
        }
        throw IOException("File changed while reading: $path")
    }
}
