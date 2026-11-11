package com.taktey.simplepdfreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import java.io.File

/** UI fixture only: Robolectric does not implement the Android native PDF engine. */
class PreviewPdfSource(override val file: File) : PdfSource {
    override val pageCount = 3
    override suspend fun pageSize(index: Int) = PageSize(595, 842)
    override suspend fun render(index: Int, requestedWidth: Int): Bitmap {
        val bitmap = createBitmap(595, 842)
        val canvas = Canvas(bitmap)
        val ink = Paint().apply { color = Color.rgb(37, 92, 74); isAntiAlias = true }
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(48f, 48f, 547f, 160f, ink)
        ink.color = Color.WHITE
        ink.textSize = 28f
        canvas.drawText("A little space to read.", 68f, 104f, ink)
        ink.textSize = 12f
        canvas.drawText("SIMPLE PDF READER / ${index + 1}", 68f, 134f, ink)
        ink.color = Color.DKGRAY
        ink.textSize = 20f
        canvas.drawText("Your documents, close at hand", 48f, 218f, ink)
        ink.textSize = 13f
        listOf("Open a document. Take your time.", "Scroll through pages and zoom into the details.",
            "Share the original PDF whenever you need to.").forEachIndexed { line, text ->
            canvas.drawText(text, 48f, 258f + line * 25f, ink)
        }
        ink.color = Color.LTGRAY
        repeat(12) { line -> canvas.drawRect(48f, 372f + line * 24, 520f - (line % 3) * 35, 378f + line * 24, ink) }
        return bitmap
    }
    override fun close() = Unit
}
