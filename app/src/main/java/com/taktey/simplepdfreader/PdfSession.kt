package com.taktey.simplepdfreader

import android.content.Context
import android.graphics.Bitmap
import android.os.*
import android.system.*
import android.util.LruCache
import androidx.core.graphics.createBitmap
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PageSize(val width: Int, val height: Int) {
    val ratio: Float get() = width.toFloat() / height
}

object RenderSize {
    const val MAX_PIXELS = 4_000_000
    fun fit(page: PageSize, requestedWidth: Int): PageSize {
        val width = requestedWidth.coerceIn(1, 4096).toDouble()
        val height = width * page.height.coerceAtLeast(1) / page.width.coerceAtLeast(1)
        val factor = minOf(1.0, sqrt(MAX_PIXELS / (width * height)), 8192.0 / height)
        return PageSize((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1))
    }
    fun valid(size: PageSize) = size.width in 1..4096 && size.height in 1..8192 &&
        size.width.toLong() * size.height <= MAX_PIXELS
}

interface PdfSource : Closeable {
    val file: File
    val pageCount: Int
    suspend fun pageSize(index: Int): PageSize
    suspend fun render(index: Int, requestedWidth: Int): Bitmap
}

class PdfSession private constructor(override val file: File, private val channel: WorkerChannel,
                                     override val pageCount: Int, internal val workerUid: Int) : PdfSource {
    private val serial = Mutex()
    private val sizes = mutableMapOf<Int, PageSize>()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var closed = false

    override suspend fun pageSize(index: Int): PageSize = serial.withLock {
        check(!closed)
        sizes[index] ?: channel.request(WorkerProtocol.SIZE, Bundle().apply { putInt("page", index) }).let {
            PageSize(it.getInt("width"), it.getInt("height")).also { size ->
                require(size.width > 0 && size.height > 0)
                sizes[index] = size
            }
        }
    }

    override suspend fun render(index: Int, requestedWidth: Int): Bitmap = serial.withLock {
        check(!closed)
        val key = "$index:$requestedWidth"
        cache.get(key) ?: withContext(Dispatchers.IO + NonCancellable) {
            // The UI owns the only read end. The worker receives only the write end,
            // so it cannot drain the pipe between poll and read or supply a blocking FD.
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                val response = channel.request(WorkerProtocol.RENDER, Bundle().apply {
                    putInt("page", index); putInt("width", requestedWidth.coerceIn(1, 4096))
                    putParcelable("output", pipe[1])
                })
                pipe[1].close()
                val size = PageSize(response.getInt("width"), response.getInt("height"))
                require(RenderSize.valid(size))
                // Raw bounded pixels only: never decode worker-supplied PNG/JPEG in the UI process.
                val bytes = readPixels(pipe[0], size.width * size.height * 4)
                val bitmap = createBitmap(size.width, size.height)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                if (!closed) cache.put(key, bitmap)
                bitmap
            } finally { pipe.forEach { runCatching { it.close() } } }
        }
    }

    private fun readPixels(fd: ParcelFileDescriptor, count: Int): ByteArray {
        val poll = StructPollfd().apply { this.fd = fd.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        val bytes = ByteArray(count)
        val deadline = SystemClock.elapsedRealtime() + WorkerProtocol.RENDER_TIMEOUT
        var offset = 0
        while (offset < count) {
            if (closed) throw WorkerFailure()
            if (SystemClock.elapsedRealtime() >= deadline) { close(); throw WorkerTimeout() }
            if (Os.poll(arrayOf(poll), 100) == 0) continue
            val read = try { Os.read(fd.fileDescriptor, bytes, offset, count - offset) }
            catch (error: ErrnoException) { if (error.errno == OsConstants.EAGAIN) continue else throw error }
            if (read == 0) throw EOFException()
            offset += read
        }
        return bytes
    }

    override fun close() {
        closed = true
        cache.evictAll()
        channel.close()
    }

    companion object {
        suspend fun open(context: Context, file: File): PdfSession {
            val channel = WorkerChannel(context.applicationContext, PdfRenderService::class.java)
            try {
                channel.connect()
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    val response = channel.request(WorkerProtocol.OPEN, Bundle().apply { putParcelable("document", fd) })
                    val pages = response.getInt("pages")
                    require(pages in 1..50_000)
                    return PdfSession(file, channel, pages, response.getInt("uid"))
                }
            } catch (error: Exception) { channel.close(); throw error }
        }
    }
}
