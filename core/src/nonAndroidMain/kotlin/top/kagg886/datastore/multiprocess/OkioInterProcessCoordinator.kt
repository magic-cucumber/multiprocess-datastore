package top.kagg886.datastore.multiprocess

import androidx.datastore.core.InterProcessCoordinator
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import top.kagg886.datastore.multiprocess.util.FileChangeListener
import top.kagg886.datastore.multiprocess.util.FileLock
import top.kagg886.datastore.multiprocess.util.SharedCounter
import kotlin.time.Duration.Companion.milliseconds

public class OkioInterProcessCoordinator(
    public val path: Path,
    public val fs: FileSystem,
    public val scope: CoroutineScope,
) : InterProcessCoordinator {
    private val scopeJob = requireNotNull(scope.coroutineContext[Job]) {
        "The coordinator scope must contain a Job"
    }
    private val mutex = Mutex()
    private val counterLock = SynchronizedObject()
    private var counter: SharedCounter? = null
    private var closed = false

    private val file by lazy {
        val absolutePath = if (path.isAbsolute) path.normalized() else fs.canonicalize(".".toPath()) / path
        val parent = requireNotNull(absolutePath.parent) { "DataStore path must have a parent: $path" }
        fs.createDirectories(parent)
        if (fs.exists(absolutePath)) fs.canonicalize(absolutePath)
        else fs.canonicalize(parent) / absolutePath.name
    }

    private val lockFile by lazy {
        val target = requireNotNull(file.parent) / "${file.name}.lock"
        // Opening without truncation preserves the inode used by other processes' locks.
        fs.openReadWrite(target).close()
        target
    }

    init {
        scopeJob.invokeOnCompletion {
            synchronized(counterLock) {
                closed = true
                counter?.close()
                counter = null
            }
        }
    }

    override val updateNotifications: Flow<Unit> = callbackFlow {
        scopeJob.ensureActive()
        while (!fs.exists(file)) {
            scopeJob.ensureActive()
            delay(100.milliseconds)
        }
        val handle = FileChangeListener.observe(file, object : FileChangeListener {
            override fun onFileContentChanged() {
                trySend(Unit)
            }

            override fun onListenerTerminated() {
                scope.cancel("File listener terminated for $file")
            }
        })
        // Recheck after installing the observer to cover writes before observation started.
        trySend(Unit)
        awaitClose { handle.close() }
    }
        .buffer(Channel.CONFLATED)
        .flowOn(Dispatchers.IO)

    override suspend fun <T> lock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            var interval = 1L
            var handle: FileLock? = null
            while (handle == null) {
                currentCoroutineContext().ensureActive()
                scopeJob.ensureActive()
                handle = FileLock.tryLock(lockFile, shared = false)
                if (handle == null) {
                    delay(interval.milliseconds)
                    interval = (interval * 2).coerceAtMost(64)
                }
            }
            handle.use {
                currentCoroutineContext().ensureActive()
                scopeJob.ensureActive()
                block()
            }
        }
    }

    override suspend fun <T> tryLock(block: suspend (Boolean) -> T): T = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        scopeJob.ensureActive()
        if (!mutex.tryLock()) return@withContext block(false)
        try {
            FileLock.tryLock(lockFile, shared = true).use { handle ->
                currentCoroutineContext().ensureActive()
                scopeJob.ensureActive()
                block(handle != null)
            }
        } finally {
            mutex.unlock()
        }
    }

    override suspend fun getVersion(): Int = withCounter { it.getValue() }

    override suspend fun incrementAndGetVersion(): Int = withCounter { it.incrementAndGetValue() }

    private suspend fun <T> withCounter(block: (SharedCounter) -> T): T {
        scopeJob.ensureActive()
        synchronized(counterLock) {
            check(!closed) { "Coordinator has already been closed: $path" }
            counter?.let { return block(it) }
        }
        return withContext(Dispatchers.IO) {
            scopeJob.ensureActive()
            synchronized(counterLock) {
                check(!closed) { "Coordinator has already been closed: $path" }
                val sharedCounter = counter ?: SharedCounter.new(
                    requireNotNull(file.parent) / "${file.name}.version"
                ).also { counter = it }
                block(sharedCounter)
            }
        }
    }
}
