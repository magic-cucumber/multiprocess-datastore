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
public interface FileChangeListenerHandle : AutoCloseable {
    @VisibleForTesting
    public val released: AtomicBoolean

    // don't call it directly
    public fun release()

    public override fun close() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) release()
    }
}

@VisibleForTesting
public interface FileChangeListener {
    /** File metadata or identity changed; notifications may coalesce multiple writes. */
    public fun onFileContentChanged()
    /** Deletion or an internal failure ended observation. Native resources are already released. */
    public fun onListenerTerminated()

    public companion object {
        private val lock = SynchronizedObject()
        private val registry = mutableMapOf<Path, Entry>()

        public fun observe(path: Path, listener: FileChangeListener): AutoCloseable {
            require(FileSystem.SYSTEM.metadata(path).isRegularFile) {
                "Listener path must be a regular file: $path"
            }
            val file = FileSystem.SYSTEM.canonicalize(path)
            val subscription = Subscription(listener)
            val entry = synchronized(lock) {
                registry.getOrPut(file) { Entry() }.also { it.listeners.add(subscription) }
            }
            // Startup is serialized per entry, without holding the registry lock.
            // Native callbacks are buffered until the handle has been installed.
            try {
                start(file, entry)
            } catch (error: Throwable) {
                subscription.close()
                throw error
            }
            return AutoCloseable {
                if (!subscription.close()) return@AutoCloseable
                val dispose = synchronized(lock) {
                    entry.listeners.remove(subscription)
                    if (entry.listeners.isEmpty() && !entry.ended) {
                        entry.ended = true
                        if (registry[file] === entry) registry.remove(file)
                        entry.impl.also { entry.impl = null }
                    } else null
                }
                // Waiting for native cancellation must never hold the registry lock.
                dispose?.close()
            }
        }

        private fun start(file: Path, entry: Entry) {
            var pendingChanged = false
            var pendingTerminated = false
            synchronized(entry.startLock) {
                entry.startupFailure?.let { throw it }
                if (entry.started) return@synchronized
                entry.started = true
                try {
                    val impl = platformFileChangedListenerCreate(file, object : InternalFileChangeListener {
                        override fun onFileContentChanged() = changed(file, entry)
                        override fun onListenerTerminated() = terminated(file, entry)
                    })
                    synchronized(lock) {
                        entry.impl = if (entry.ended) null else impl
                        entry.ready = true
                        pendingChanged = entry.pendingChange && !entry.ended
                        pendingTerminated = entry.pendingTermination
                    }
                } catch (error: Throwable) {
                    entry.startupFailure = error
                    synchronized(lock) {
                        entry.ended = true
                        entry.listeners.clear()
                        if (registry[file] === entry) registry.remove(file)
                    }
                    throw error
                }
            }
            if (pendingTerminated) deliverTermination(entry)
            else if (pendingChanged) changed(file, entry)
        }

        private fun changed(file: Path, entry: Entry) {
            val listeners = synchronized(lock) {
                if (entry.ended || registry[file] !== entry) return
                if (!entry.ready) { entry.pendingChange = true; return }
                entry.listeners.toList()
            }
            listeners.forEach { it.changed() }
        }

        private fun terminated(file: Path, entry: Entry) {
            synchronized(lock) {
                if (entry.ended) return
                entry.ended = true
                entry.impl = null // The platform has already disposed of its native object.
                if (registry[file] === entry) registry.remove(file)
                if (!entry.ready) { entry.pendingTermination = true; return }
            }
            deliverTermination(entry)
        }

        private fun deliverTermination(entry: Entry) {
            val listeners = synchronized(lock) {
                entry.listeners.toList().also { entry.listeners.clear() }
            }
            listeners.forEach { it.terminated() }
        }

        private class Entry {
            val startLock = SynchronizedObject()
            var started = false // Protected by startLock.
            var startupFailure: Throwable? = null // Protected by startLock.
            var impl: FileChangeListenerHandle? = null
            val listeners = mutableSetOf<Subscription>()
            var ready = false
            var ended = false
            var pendingChange = false
            var pendingTermination = false
        }

        private class Subscription(private val listener: FileChangeListener) {
            private val callbackLock = SynchronizedObject()
            private var closed = false
            fun close(): Boolean = synchronized(callbackLock) {
                if (closed) false else { closed = true; true }
            }
            fun changed() = synchronized(callbackLock) {
                if (!closed) runCatching { listener.onFileContentChanged() }
                Unit
            }
            fun terminated() = synchronized(callbackLock) {
                if (!closed) {
                    closed = true
                    runCatching { listener.onListenerTerminated() }
                }
                Unit
            }
        }
    }
}

@VisibleForTesting
public interface InternalFileChangeListener {
    public fun onFileContentChanged()
    public fun onListenerTerminated()
}


internal expect fun platformFileChangedListenerCreate(
    file: Path,
    listener: InternalFileChangeListener
): FileChangeListenerHandle
