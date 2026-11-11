package com.taktey.simplepdfreader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.core.graphics.createBitmap
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PageSize(val width: Int, val height: Int) {
    val ratio: Float get() = width.toFloat() / height
}

object RenderSize {
    // At most 16 MiB per rendered page, even for a huge or unusually tall PDF.
    fun fit(page: PageSize, requestedWidth: Int): PageSize {
        val width = requestedWidth.coerceIn(1, 4096).toDouble()
        val height = width * page.height / page.width.coerceAtLeast(1)
        val factor = minOf(1.0, sqrt(4_000_000.0 / (width * height)), 8192.0 / height)
        return PageSize((width * factor).roundToInt().coerceAtLeast(1),
            (height * factor).roundToInt().coerceAtLeast(1))
    }
}

interface PdfSource : Closeable {
    val file: File
    val pageCount: Int
    suspend fun pageSize(index: Int): PageSize
    suspend fun render(index: Int, requestedWidth: Int): Bitmap
}

class PdfSession(override val file: File) : PdfSource {
    private val lock = Any()
    private val renderer: PdfRenderer
    private var closed = false
    override val pageCount: Int
    private val sizes = mutableMapOf<Int, PageSize>()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // Never recycle on eviction: Compose may still be displaying this bitmap.
    }

    init {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try { renderer = PdfRenderer(descriptor) }
        catch (error: Throwable) { descriptor.close(); throw error }
        pageCount = renderer.pageCount
        if (pageCount < 1) { renderer.close(); throw InvalidDocument() }
    }

    override suspend fun pageSize(index: Int): PageSize = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureActive()
            check(!closed)
            sizes.getOrPut(index) {
                renderer.openPage(index).use { PageSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
            }
        }
    }

    override suspend fun render(index: Int, requestedWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureActive()
            check(!closed)
            val key = "$index:$requestedWidth"
            cache.get(key) ?: renderer.openPage(index).use { page ->
                val size = RenderSize.fit(PageSize(page.width, page.height), requestedWidth)
                val bitmap = createBitmap(size.width, size.height)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ensureActive()
                    cache.put(key, bitmap)
                    bitmap
                } catch (error: Throwable) { bitmap.recycle(); throw error }
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            cache.evictAll()
            renderer.close()
        }
    }
}
