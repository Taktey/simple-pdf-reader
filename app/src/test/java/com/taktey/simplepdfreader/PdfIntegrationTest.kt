package com.taktey.simplepdfreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfIntegrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun attachProviderForThisSandbox() {
        // AndroidX keeps a static path cache; each Robolectric test has a new filesDir.
        val info = context.packageManager.resolveContentProvider("${context.packageName}.files", 128)!!
        FileProvider().attachInfo(context, info)
    }

    @Test fun shareContainsReadablePdfAttachmentAndTemporaryReadGrant() {
        val file = samplePdf(context)
        val intent = PdfIntents.share(context, file)
        val uri = PdfIntents.incoming(intent)!!
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals("content", uri.scheme)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun importsPdfBytesAndRestoresItsPrivateCopy() = runBlocking {
        val file = samplePdf(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val store = DocumentStore(context)
        val imported = store.import(uri)
        assertArrayEquals(file.readBytes(), imported.readBytes())
        assertEquals(imported.canonicalFile, store.restore(store.relativePath(imported)))
    }

    @Test fun incomingIntentsSupportOpenAndShareButIgnoreOtherActionsAndSchemes() {
        val uri = Uri.parse("content://documents/example.pdf")
        assertEquals(uri, PdfIntents.incoming(Intent(Intent.ACTION_VIEW, uri)))
        assertEquals(uri, PdfIntents.incoming(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
        assertNull(PdfIntents.incoming(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/a.pdf"))))
        assertNull(PdfIntents.incoming(Intent(Intent.ACTION_MAIN, uri)))
    }

    @Test fun recentDocumentsSurviveStoreRecreationAndExcludeMissingFiles() = runBlocking {
        val original = samplePdf(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", original)
        val store = DocumentStore(context)
        val first = store.import(uri)
        val second = store.import(uri)
        first.setLastModified(1000)
        second.setLastModified(2000)
        val recreated = DocumentStore(context)
        val documents = recreated.recentDocuments().filter { it.path != store.relativePath(original) }
        assertEquals(listOf(store.relativePath(second), store.relativePath(first)), documents.map { it.path })
        assertEquals(second.canonicalFile, recreated.restore(documents.first().path))
        second.delete()
        assertFalse(recreated.recentDocuments().any { it.path == store.relativePath(second) })
        File(first.parentFile, ".importing").writeText("incomplete")
        assertFalse(recreated.recentDocuments().any { it.name == ".importing" })
    }

    @Test(expected = DocumentAccessDenied::class) fun cannotRestoreOutsideDocumentDirectory() {
        DocumentStore(context).restore("../../shared_prefs/private.xml")
    }
}

fun samplePdf(context: Context): File {
    val file = File(context.filesDir, "documents/sample/Reading guide.pdf")
    file.parentFile!!.mkdirs()
    requireNotNull(PdfIntegrationTest::class.java.getResourceAsStream("/sample.pdf"))
        .use { source -> file.outputStream().use(source::copyTo) }
    return file
}
