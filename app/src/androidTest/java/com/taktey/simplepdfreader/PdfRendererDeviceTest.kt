package com.taktey.simplepdfreader

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on Android, where the real system PDF engine is available. */
@RunWith(AndroidJUnit4::class)
class PdfRendererDeviceTest {
    @Test fun rendersActualPdfAndClosesNativeResources() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "renderer-test.pdf")
        instrumentation.context.assets.open("sample.pdf").use { input ->
            file.outputStream().use(input::copyTo)
        }
        try {
            PdfSession.open(instrumentation.targetContext, file).use { session ->
                assertNotEquals(android.os.Process.myUid(), session.workerUid)
                assertEquals(3, session.pageCount)
                assertEquals(PageSize(595, 842), session.pageSize(0))
                val image = session.render(0, 595)
                assertEquals(595, image.width)
                assertEquals(842, image.height)
                assertEquals(Color.WHITE, image.getPixel(0, 0))
                assertEquals(Color.rgb(37, 92, 74), image.getPixel(60, 60))
                assertSame(image, session.render(0, 595))
                assertNotNull(session.render(2, 1190))
            }
        } finally { file.delete() }
    }

    @Test fun rejectsMalformedDocument() = runBlocking {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "invalid.pdf")
        file.writeText("not a PDF")
        try {
            try { PdfSession.open(InstrumentationRegistry.getInstrumentation().targetContext, file).use { fail("Invalid PDF was accepted") } }
            catch (_: java.io.IOException) { /* Expected from the platform renderer. */ }
        } finally { file.delete() }
    }
}
