package com.taktey.simplepdfreader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.*
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer

/** The only production code allowed to construct the native PDF parser. */
class PdfRenderService : BoundedWorkerService() {
    private var renderer: PdfRenderer? = null

    override fun execute(operation: Int, data: Bundle, reply: (Bundle) -> Unit) {
        val outputPipe = if (operation == WorkerProtocol.RENDER) data.descriptor("output") ?: throw InvalidDocument() else null
        try { when (operation) {
            WorkerProtocol.OPEN -> {
                renderer?.close()
                renderer = null
                val fd = data.descriptor("document") ?: throw InvalidDocument()
                val opened = try { PdfRenderer(fd) } catch (error: Throwable) {
                    fd.close()
                    if (error is java.io.IOException) throw InvalidDocument()
                    throw error
                }
                if (opened.pageCount !in 1..50_000) { opened.close(); throw InvalidDocument() }
                renderer = opened
                reply(Bundle().apply { putInt("pages", opened.pageCount); putInt("uid", Process.myUid()) })
            }
            WorkerProtocol.SIZE, WorkerProtocol.RENDER -> {
                val document = renderer ?: throw InvalidDocument()
                val index = data.getInt("page", -1)
                require(index in 0 until document.pageCount)
                document.openPage(index).use { page ->
                    val size = PageSize(page.width.coerceAtLeast(1), page.height.coerceAtLeast(1))
                    if (operation == WorkerProtocol.SIZE) {
                        reply(Bundle().apply { putInt("width", size.width); putInt("height", size.height) })
                    } else {
                        val output = RenderSize.fit(size, data.getInt("width"))
                        val bitmap = createBitmap(output.width, output.height)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pixels = ByteBuffer.allocate(bitmap.byteCount)
                            bitmap.copyPixelsToBuffer(pixels)
                            reply(Bundle().apply {
                                putInt("width", output.width); putInt("height", output.height)
                            })
                            ParcelFileDescriptor.AutoCloseOutputStream(requireNotNull(outputPipe)).use { it.write(pixels.array()) }
                        } finally { bitmap.recycle() }
                    }
                }
            }
            else -> throw InvalidDocument()
        } } finally { outputPipe?.close() }
    }
}
