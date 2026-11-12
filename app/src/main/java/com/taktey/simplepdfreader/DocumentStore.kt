package com.taktey.simplepdfreader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DocumentTooLarge : IOException()
class InvalidDocument : IOException()
class DocumentAccessDenied : IOException()
data class RecentDocument(val path: String, val name: String, val openedAt: Long)
data class ImportedDocument(val name: String, val digest: String)

object DocumentFiles {
    const val MAX_BYTES = 200L * 1024 * 1024
    fun safeName(name: String?): String {
        val clean = name.orEmpty().take(100).replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_")
            .trim().trim('.').ifBlank { "document.pdf" }
        return if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
    }
    fun copyBounded(input: InputStream, output: OutputStream, limit: Long = MAX_BYTES,
                    validatePdf: Boolean = false, checkCancelled: () -> Unit = {}) {
        val buffer = ByteArray(64 * 1024)
        val header = ByteArrayOutputStream(1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total + count > limit) throw DocumentTooLarge()
            if (validatePdf && header.size() < 1024) header.write(buffer, 0, minOf(count, 1024 - header.size()))
            total += count
            if (validatePdf && header.size() == 1024 && !validHeader(header.toByteArray())) throw InvalidDocument()
            output.write(buffer, 0, count)
        }
        if (total == 0L || (validatePdf && !validHeader(header.toByteArray()))) throw InvalidDocument()
    }
    private fun validHeader(bytes: ByteArray) = bytes.toString(Charsets.ISO_8859_1).contains("%PDF-")
}

fun interface DocumentImporter {
    suspend fun copy(context: Context, uri: Uri, output: File, limit: Long): ImportedDocument
}

internal object RemoteDocumentImporter : DocumentImporter {
    override suspend fun copy(context: Context, uri: Uri, output: File, limit: Long): ImportedDocument = coroutineScope {
        val channel = WorkerChannel(context.applicationContext, DocumentImportService::class.java)
        // Cancellation closes the binding immediately even while a provider blocks in Binder/read.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { channel.close() }
        }
        try {
            channel.connect()
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { fd ->
                val result = channel.request(WorkerProtocol.IMPORT, Bundle().apply {
                    putString("uri", uri.toString()); putParcelable("output", fd); putLong("limit", limit)
                }, WorkerProtocol.IMPORT_TIMEOUT)
                ensureActive()
                ImportedDocument(result.getString("name") ?: "document.pdf", result.getString("digest") ?: throw InvalidDocument())
            }
        } finally { cancellation.cancel(); channel.close() }
    }
}

class DocumentStore(private val context: Context, private val importer: DocumentImporter = RemoteDocumentImporter,
                    private val quota: Long = MAX_STORE_BYTES, private val fileLimit: Long = DocumentFiles.MAX_BYTES,
                    private val freeReserve: Long = MIN_FREE_BYTES) {
    private val root get() = File(context.filesDir, "documents").canonicalFile.apply { mkdirs() }
    private val shared get() = File(context.filesDir, "shared").canonicalFile.apply { mkdirs() }
    private val pending get() = File(context.cacheDir, "imports").apply { mkdirs() }

    suspend fun import(uri: Uri, keep: File? = null): File = storageMutex.withLock {
        if (uri.scheme != "content") throw DocumentAccessDenied()
        withContext(Dispatchers.IO) { ensureRoom(fileLimit, keep) }
        val temporary = File(pending, UUID.randomUUID().toString())
        try {
            val imported = importer.copy(context, uri, temporary, fileLimit)
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) {
                require(imported.digest.matches(Regex("[0-9a-f]{64}")))
                if (temporary.length() !in 1..fileLimit) throw InvalidDocument()
                val directory = File(root, imported.digest).apply { mkdirs() }
                val existing = directory.listFiles()?.firstOrNull { it.isFile && it.extension.equals("pdf", true) }
                if (existing != null) { markOpened(existing); existing }
                else {
                    val destination = File(directory, DocumentFiles.safeName(imported.name))
                    if (!temporary.renameTo(destination)) throw IOException("Cannot finish document import")
                    destination
                }
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { temporary.delete() } }
    }

    suspend fun prepareShare(file: File): File = storageMutex.withLock {
        withContext(Dispatchers.IO) {
            val original = restore(relativePath(file))
            ensureRoom(original.length(), original)
            val directory = File(shared, UUID.randomUUID().toString()).apply { mkdirs() }
            val copy = File(directory, DocumentFiles.safeName(original.name))
            try { original.copyTo(copy); copy.setLastModified(System.currentTimeMillis()); copy }
            catch (error: Exception) { directory.deleteRecursively(); throw error }
        }
    }

    private fun files(directory: File): List<File> = directory.listFiles().orEmpty()
        .filter { it.isDirectory && it.canonicalFile.parentFile == directory }
        .flatMap { it.listFiles().orEmpty().toList() }
        .filter { it.isFile && it.canonicalFile.parentFile?.parentFile == directory }

    private fun ensureRoom(bytes: Long, keep: File?) {
        cleanOldCopies(keep)
        val keepPath = keep?.canonicalFile
        var used = files(root).sumOf { it.length() } + files(shared).sumOf { it.length() }
        val evictable = files(root).filter { it.canonicalFile != keepPath }.sortedBy { it.lastModified() }
        for (file in evictable) {
            if (used + bytes <= quota && root.usableSpace >= bytes + freeReserve) break
            val size = file.length()
            if (file.delete()) { used -= size; file.parentFile?.delete() }
        }
        if (used + bytes > quota || root.usableSpace < bytes + freeReserve) throw StorageFull()
    }

    fun relativePath(file: File): String = file.canonicalFile.relativeTo(root).path
    fun restore(relativePath: String): File {
        val file = File(root, relativePath).canonicalFile
        if (file.parentFile?.parentFile != root || !file.isFile || !file.extension.equals("pdf", true)) throw DocumentAccessDenied()
        return file
    }
    fun recentDocuments(): List<RecentDocument> = files(root)
        .filter { it.extension.equals("pdf", true) && it.length() > 0 }
        .map { RecentDocument(relativePath(it), it.name, it.lastModified()) }
        .sortedByDescending { it.openedAt }.take(20)
    fun markOpened(file: File) {
        val now = System.currentTimeMillis()
        file.setLastModified(now); file.parentFile?.setLastModified(now)
    }
    suspend fun deleteDocument(path: String) = storageMutex.withLock {
        withContext(Dispatchers.IO) { val file = restore(path); if (!file.delete()) throw IOException("Cannot delete document"); file.parentFile?.delete() }
    }
    suspend fun clearDocuments() = storageMutex.withLock {
        withContext(Dispatchers.IO) { if (!root.deleteRecursively() || !shared.deleteRecursively() || !pending.deleteRecursively()) throw IOException("Cannot clear documents") }
    }
    suspend fun refreshRecent(keep: File?): List<RecentDocument> = storageMutex.withLock {
        withContext(Dispatchers.IO) { cleanOldCopies(keep); recentDocuments() }
    }
    private fun cleanOldCopies(keep: File?) {
        val now = System.currentTimeMillis()
        files(root).filter { it.canonicalFile != keep?.canonicalFile && it.lastModified() < now - DOCUMENT_AGE }
            .forEach { it.delete(); it.parentFile?.delete() }
        files(shared).filter { it.lastModified() < now - SHARE_AGE }
            .forEach { it.delete(); it.parentFile?.delete() }
        // Crashed or killed imports have no public URI and can be discarded on startup.
        pending.listFiles()?.filter { it.lastModified() < now - 60_000 }?.forEach { it.delete() }
    }
    companion object {
        const val MAX_STORE_BYTES = 500L * 1024 * 1024
        const val MIN_FREE_BYTES = 50L * 1024 * 1024
        const val SHARE_AGE = 24L * 60 * 60 * 1000
        const val DOCUMENT_AGE = 7L * 24 * 60 * 60 * 1000
        private val storageMutex = Mutex()
    }
}
