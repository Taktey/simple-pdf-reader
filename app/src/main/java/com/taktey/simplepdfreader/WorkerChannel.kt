package com.taktey.simplepdfreader

import android.app.Service
import android.content.*
import android.os.*
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkerFailure : IOException()
class WorkerTimeout : IOException()
class StorageFull : IOException()

internal object WorkerProtocol {
    const val OPEN = 1
    const val SIZE = 2
    const val RENDER = 3
    const val IMPORT = 4
    const val RENDER_TIMEOUT = 15_000L
    const val IMPORT_TIMEOUT = 45_000L
}

@Suppress("DEPRECATION")
internal fun Bundle.descriptor(key: String): ParcelFileDescriptor? = getParcelable(key)

/** A bounded asynchronous Binder channel. No remote call runs on the UI thread. */
internal class WorkerChannel(private val context: Context, serviceClass: Class<out Service>) : Closeable {
    private val intent = Intent(context, serviceClass)
    private val main = Handler(Looper.getMainLooper())
    private val ready = CompletableDeferred<Messenger>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val serial = Mutex()
    private val sequence = AtomicInteger()
    private val closed = AtomicBoolean()
    private var bound = false
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        val response = message.data
        val target = pending.remove(message.arg1)
        target?.complete(response)
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(Messenger(binder)) }
        override fun onServiceDisconnected(name: ComponentName) { close() }
        override fun onBindingDied(name: ComponentName) { close() }
        override fun onNullBinding(name: ComponentName) { close() }
    }

    suspend fun connect() {
        try {
            withContext(Dispatchers.Main.immediate) {
                check(!closed.get())
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) throw WorkerFailure()
            }
            withTimeout(5_000) { ready.await() }
        } catch (error: Exception) { close(); throw error }
    }

    suspend fun request(operation: Int, data: Bundle = Bundle(), timeout: Long = WorkerProtocol.RENDER_TIMEOUT): Bundle = serial.withLock {
        // Finish an already submitted operation before allowing another one. Scrolling may
        // cancel the caller, but must not queue unbounded native render operations.
        withContext(NonCancellable) {
            if (closed.get()) throw WorkerFailure()
            val id = sequence.incrementAndGet()
            val result = CompletableDeferred<Bundle>()
            pending[id] = result
            var response: Bundle? = null
            try {
                response = withTimeout(timeout + 2_000) {
                    withContext(Dispatchers.IO) {
                        ready.await().send(Message.obtain().apply {
                            what = operation; arg1 = id; replyTo = replies; this.data = data
                        })
                    }
                    result.await()
                }
                when (response.getString("error")) {
                    "password" -> throw SecurityException("Password protected PDF")
                    "access" -> throw DocumentAccessDenied()
                    "large" -> throw DocumentTooLarge()
                    "storage" -> throw StorageFull()
                    "invalid" -> throw InvalidDocument()
                    null -> Unit
                    else -> throw WorkerFailure()
                }
                response
            } catch (_: TimeoutCancellationException) {
                close()
                throw WorkerTimeout()
            } catch (error: Exception) {
                throw error
            } finally {
                pending.remove(id)
                result.cancel()
            }
        }
    }

    private fun fail() {
        ready.completeExceptionally(WorkerFailure())
        pending.values.forEach { it.completeExceptionally(WorkerFailure()) }
        pending.clear()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        fail()
        main.post {
            if (bound) { runCatching { context.unbindService(connection) }; bound = false }
        }
    }
}
