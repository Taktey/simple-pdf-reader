package com.taktey.simplepdfreader

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DocumentFilesTest {
    @Test fun preservesUnicodeNamesAndRemovesPaths() {
        assertEquals("Договор.pdf", DocumentFiles.safeName("Договор.pdf"))
        val name = DocumentFiles.safeName("../../secret\\file\n.pdf")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains('\n'))
        assertEquals("document.pdf", DocumentFiles.safeName(null))
    }

    @Test fun shareCopyPreservesEveryByte() {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val destination = ByteArrayOutputStream()
        DocumentFiles.copyBounded(ByteArrayInputStream(bytes), destination, bytes.size.toLong())
        assertArrayEquals(bytes, destination.toByteArray())
    }

    @Test(expected = DocumentTooLarge::class) fun enforcesSizeWhileReadingWithoutTrustingMetadata() {
        DocumentFiles.copyBounded(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), 99)
    }

    @Test(expected = InvalidDocument::class) fun rejectsEmptyFiles() {
        DocumentFiles.copyBounded(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
    }

    @Test(expected = CancellationException::class) fun cancelledImportStopsCopying() {
        DocumentFiles.copyBounded(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream()) {
            throw CancellationException()
        }
    }

    @Test fun renderBudgetHandlesHugeAndVeryTallPages() {
        for (page in listOf(PageSize(595, 842), PageSize(1, 100000), PageSize(100000, 1))) {
            val size = RenderSize.fit(page, 12000)
            assertTrue(size.width > 0 && size.height > 0)
            assertTrue(size.width.toLong() * size.height <= 4_010_000)
            assertTrue(size.height <= 8192)
        }
    }
}
