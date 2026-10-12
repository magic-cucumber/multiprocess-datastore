package top.kagg886.datastore.multiprocess.util

import okio.Path
import okio.Path.Companion.toPath
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class JvmDirectoryObservation(
    private val path: Path,
    private val listener: DictionaryObserverListener,
) : AutoCloseable, InternalDictionaryObserverJNI {
    private val state = AtomicInteger(0) // active, terminated, closed
    private val stopped = CountDownLatch(1)
    private val monitor = Any()
    @Volatile private var worker: Thread? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "datastore-directory-observer").also { it.isDaemon = true; worker = it }
    }
    private val snapshot = DirectorySnapshot(path)
    private var ptr = 0L
    private var ready = false
    private var scheduled = false
    private var fullScan = false
    private val pending = mutableSetOf<Path>()
    private var failure: Int? = null
    private var retries = 0

    fun start() {
        try {
            ptr = InternalDictionaryObserver.observe0(path.toString(), this)
            if (ptr <= 0L) {
                val code = if (ptr == 0L) -1 else (-ptr).toInt()
                ptr = 0L
                throw DictionaryObserverTerminatedException(code, path)
            }
            executor.submit {
                snapshot.initialize()
                synchronized(monitor) { ready = true; schedule() }
            }.get()
        } catch (error: Throwable) {
            state.set(2)
            val cleanup = executor.submit { release() }
            try { uninterruptible { cleanup.get() } } finally { executor.shutdown() }
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val cause = if (error is ExecutionException) error.cause ?: error else error
            if (cause is Error) throw cause
            throw if (cause is DictionaryObserverTerminatedException) cause else
                DictionaryObserverTerminatedException.internal(path, cause)
        }
    }

    override fun handle(
        create: Array<String>, remove: Array<String>, modified: Array<String>,
        renamedOld: Array<String>, renameNew: Array<String>, overflow: Boolean,
    ) {
        synchronized(monitor) {
            if (state.get() != 0 || failure != null) return
            if (overflow) fullScan = true
            if (!fullScan) {
                for (paths in arrayOf(create, remove, modified, renamedOld, renameNew)) {
                    for (rawPath in paths) {
                        val target = rawPath.toPath()
                        if (target.parent == path) pending.add(target)
                        if (pending.size >= 4096) { fullScan = true; break }
                    }
                    if (fullScan) break
                }
            }
            if (fullScan) pending.clear()
            schedule()
        }
    }

    override fun terminate(cause: Int) {
        synchronized(monitor) {
            if (state.get() != 0 || failure != null) return
            failure = cause
            schedule()
        }
    }

    // Called under monitor: event volume cannot create an unbounded executor queue.
    private fun schedule(delayMillis: Long = 10L) {
        if (!ready || scheduled || state.get() != 0 || (failure == null && !fullScan && pending.isEmpty())) return
        scheduled = true
        try {
            executor.schedule({ process() }, delayMillis, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) { scheduled = false }
    }

    private fun process() {
        val batch: Pair<Set<Path>, Boolean>
        val cause: Int?
        synchronized(monitor) {
            scheduled = false
            if (state.get() != 0) return
            cause = failure
            batch = pending.toSet() to fullScan
            pending.clear()
            fullScan = false
        }
        if (cause != null) {
            finish(DictionaryObserverTerminatedException(cause, path))
            return
        }
        try {
            val events = snapshot.diff(batch.first, batch.second)
            retries = 0
            if (events.isNotEmpty() && state.get() == 0) runCatching { listener.onEvent(events) }
        } catch (error: Throwable) {
            if (++retries >= 5) {
                finish(DictionaryObserverTerminatedException.internal(path, error))
                return
            }
            synchronized(monitor) { fullScan = true; schedule(100L) }
        }
        synchronized(monitor) { schedule() }
    }

    private fun finish(exception: DictionaryObserverTerminatedException) {
        if (!state.compareAndSet(0, 1)) return
        release()
        executor.shutdown()
        runCatching { listener.onTerminated(exception) }
    }

    private fun release() {
        try {
            if (ptr != 0L) {
                InternalDictionaryObserver.stopObserve0(ptr)
                ptr = 0L
            }
        } finally { stopped.countDown() }
    }

    override fun close() {
        if (state.compareAndSet(0, 2)) {
            if (Thread.currentThread() === worker) {
                release()
                executor.shutdown()
            } else {
                val cleanup = executor.submit { release() }
                try {
                    uninterruptible { cleanup.get() }
                } finally {
                    executor.shutdown()
                    uninterruptible { executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
                }
            }
        } else if (Thread.currentThread() !== worker) {
            uninterruptible {
                stopped.await()
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            }
        }
    }

    private fun <T> uninterruptible(action: () -> T): T {
        var interrupted = false
        try {
            while (true) {
                try { return action() } catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
