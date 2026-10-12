package top.kagg886.datastore.multiprocess.util

import androidx.annotation.VisibleForTesting
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/10/12 11:14
 * ================================================
 */

@VisibleForTesting
public object DictionaryObserver {
    private val lock = SynchronizedObject()
    private val registry = mutableMapOf<Path, Entry>()

    /**
     * Shares one native watcher per canonical directory. Callbacks run on its serial background thread.
     * A new registration after termination starts a new baseline; it does not replay the downtime.
     */
    public fun observe(
        path: Path,
        block: DictionaryObserverListener,
    ): AutoCloseable {
        require(FileSystem.SYSTEM.metadata(path).isDirectory) {
            "Listener path must be a directory: $path"
        }
        val directory = FileSystem.SYSTEM.canonicalize(path)
        val subscription = Subscription(block)
        var start = false
        val entry = synchronized(lock) {
            val value = registry[directory] ?: Entry().also {
                registry[directory] = it
                start = true
            }
            value.listeners.add(subscription)
            value
        }
        if (start) {
            try {
                val observer = InternalDictionaryObserver.observe(directory, object : DictionaryObserverListener {
                    override fun onEvent(events: List<DictionaryChangeEvent>) {
                        val listeners = synchronized(lock) {
                            if (entry.terminated) emptyList() else entry.listeners.toList()
                        }
                        listeners.forEach { listener ->
                            listener.notify { it.onEvent(events) }
                        }
                    }
                    override fun onTerminated(exception: DictionaryObserverTerminatedException) {
                        terminate(directory, entry, exception)
                    }
                })
                val dispose = synchronized(lock) {
                    if (entry.terminated || entry.listeners.isEmpty()) true else {
                        entry.observer = observer
                        false
                    }
                }
                if (dispose) observer.close()
            } catch (error: Throwable) {
                subscription.close()
                terminate(directory, entry, if (error is DictionaryObserverTerminatedException) error else
                    DictionaryObserverTerminatedException.internal(directory, error))
                throw error
            }
        }
        return AutoCloseable {
            if (!subscription.close()) return@AutoCloseable
            val dispose = synchronized(lock) {
                entry.listeners.remove(subscription)
                if (entry.listeners.isEmpty()) {
                    if (registry[directory] === entry) registry.remove(directory)
                    entry.terminated = true
                    entry.observer.also { entry.observer = null }
                } else null
            }
            // Native thread joins must occur outside the registry lock.
            dispose?.close()
        }
    }

    private fun terminate(path: Path, entry: Entry, exception: DictionaryObserverTerminatedException) {
        val listeners = synchronized(lock) {
            if (entry.terminated) return
            entry.terminated = true
            if (registry[path] === entry) registry.remove(path)
            entry.observer = null
            entry.listeners.toList().also { entry.listeners.clear() }
        }
        listeners.forEach { subscription ->
            subscription.notify { it.onTerminated(exception) }
        }
    }

    private class Entry {
        var observer: AutoCloseable? = null
        var terminated = false
        val listeners = mutableListOf<Subscription>()
    }

    private class Subscription(val block: DictionaryObserverListener) {
        private val closed = AtomicBoolean(false)
        private val callbackLock = SynchronizedObject()

        fun close(): Boolean = synchronized(callbackLock) { closed.compareAndSet(false, true) }

        // Closing a registration waits for an already-started callback and prevents a new one.
        // The recursive platform monitor also permits a callback to close its own registration.
        fun notify(action: (DictionaryObserverListener) -> Unit) = synchronized(callbackLock) {
            if (!closed.load()) runCatching { action(block) }
            Unit
        }
    }
}

@VisibleForTesting
public data class DictionaryChangeEvent(
    val target: Path,
    val type: DictionaryChangeEventType
)

@VisibleForTesting
public enum class DictionaryChangeEventType {
    CREATE, REMOVE,
    MODIFIED,
}

@VisibleForTesting
public interface DictionaryObserverListener {
    public fun onEvent(events: List<DictionaryChangeEvent>)
    public fun onTerminated(exception: DictionaryObserverTerminatedException)
}

internal expect object InternalDictionaryObserver {
    fun observe(path: Path, block: DictionaryObserverListener): AutoCloseable
}
