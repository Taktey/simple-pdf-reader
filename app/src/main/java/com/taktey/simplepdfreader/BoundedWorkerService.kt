package com.taktey.simplepdfreader

import android.app.Service
import android.content.Intent
import android.os.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Runs only in a dedicated process. A native hang cannot stop its watchdog thread. */
abstract class BoundedWorkerService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val queued = AtomicInteger()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val reply = message.replyTo ?: return@Handler true
        val id = message.arg1
        val operation = message.what
        val data = message.data
        if (queued.incrementAndGet() > 2) {
            queued.decrementAndGet()
            send(reply, id, Bundle().apply { putString("error", "busy") })
        } else worker.execute {
            val timeout = if (operation == WorkerProtocol.IMPORT) WorkerProtocol.IMPORT_TIMEOUT else WorkerProtocol.RENDER_TIMEOUT
            val deadline = watchdog.schedule({ Process.killProcess(Process.myPid()) }, timeout, TimeUnit.MILLISECONDS)
            try { execute(operation, data) { send(reply, id, it) } }
            catch (error: Throwable) {
                val code = when (error) {
                    is DocumentTooLarge -> "large"
                    is DocumentAccessDenied -> "access"
                    is StorageFull -> "storage"
                    is SecurityException -> "password"
                    is InvalidDocument, is IllegalArgumentException -> "invalid"
                    else -> "failed"
                }
                send(reply, id, Bundle().apply { putString("error", code) })
            } finally {
                deadline.cancel(false)
                queued.decrementAndGet()
            }
        }
        true
    })

    protected abstract fun execute(operation: Int, data: Bundle, reply: (Bundle) -> Unit)
    private fun send(reply: Messenger, id: Int, data: Bundle) {
        reply.send(Message.obtain().apply { arg1 = id; this.data = data })
    }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onDestroy() {
        // This service is never hosted in the application/UI process. Terminating this
        // process also interrupts blocked Binder, stream and native PDF calls.
        worker.shutdownNow()
        watchdog.shutdownNow()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
