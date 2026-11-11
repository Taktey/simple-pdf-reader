package com.taktey.simplepdfreader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class DocumentTooLarge : IOException()
class InvalidDocument : IOException()
class DocumentAccessDenied : IOException()

object DocumentFiles {
    const val MAX_BYTES = 200L * 1024 * 1024

    fun safeName(name: String?): String {
        val clean = name.orEmpty().replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_")
            .trim().trim('.').take(100).ifBlank { "document.pdf" }
        return if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
    }

    fun copyBounded(input: InputStream, output: OutputStream, limit: Long = MAX_BYTES,
                    checkCancelled: () -> Unit = {}) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw DocumentTooLarge()
            output.write(buffer, 0, count)
        }
        if (total == 0L) throw InvalidDocument()
    }
}

/** Own copies are seekable, survive temporary incoming URI grants, and can be shared again. */
class DocumentStore(private val context: Context) {
    private val root get() = File(context.filesDir, "documents").canonicalFile.apply { mkdirs() }

    suspend fun import(uri: Uri): File {
        if (uri.scheme != "content") throw DocumentAccessDenied()
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null }
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdirs()) throw IOException("Cannot create document directory")
        val file = File(directory, DocumentFiles.safeName(name))
        var complete = false
        try {
            val jobContext = coroutineContext
            val input = try {
                context.contentResolver.openInputStream(uri) ?: throw DocumentAccessDenied()
            } catch (_: SecurityException) { throw DocumentAccessDenied() }
            input.use { source -> file.outputStream().use { destination ->
                DocumentFiles.copyBounded(source, destination) { jobContext.ensureActive() }
            } }
            complete = true
            return file
        } catch (_: SecurityException) {
            throw DocumentAccessDenied()
        } finally {
            if (!complete) directory.deleteRecursively()
        }
    }

    fun relativePath(file: File): String = file.canonicalFile.relativeTo(root).path

    fun restore(relativePath: String): File {
        val directory = root.canonicalFile
        val file = File(directory, relativePath).canonicalFile
        if (!file.path.startsWith(directory.path + File.separator) || !file.isFile) {
            throw DocumentAccessDenied()
        }
        return file
    }

    // Keep recently shared files alive for recipients; never delete the current document.
    fun cleanOldCopies(keep: File?) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        root.listFiles()?.filter { it.isDirectory && it != keep?.parentFile && it.lastModified() < cutoff }
            ?.forEach { it.deleteRecursively() }
    }
}
