package com.taktey.simplepdfreader

import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.system.Os
import java.security.DigestOutputStream
import java.security.MessageDigest

class DocumentImportService : BoundedWorkerService() {
    override fun execute(operation: Int, data: Bundle, reply: (Bundle) -> Unit) {
        require(operation == WorkerProtocol.IMPORT)
        val output = data.descriptor("output") ?: throw InvalidDocument()
        output.use {
            val uri = Uri.parse(data.getString("uri") ?: throw DocumentAccessDenied())
            if (uri.scheme != "content") throw DocumentAccessDenied()
            val signal = CancellationSignal()
            val timer = Handler(Looper.getMainLooper())
            val cancel = Runnable { signal.cancel() }
            timer.postDelayed(cancel, WorkerProtocol.IMPORT_TIMEOUT - 1_000)
            try {
                val type = contentResolver.getType(uri)
                if (type != null && type !in listOf("application/pdf", "application/octet-stream")) throw InvalidDocument()
                val name = try {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                } catch (_: SecurityException) { throw DocumentAccessDenied() }
                val input = try { contentResolver.openFileDescriptor(uri, "r", signal) ?: throw DocumentAccessDenied() }
                    catch (_: SecurityException) { throw DocumentAccessDenied() }
                val digest = MessageDigest.getInstance("SHA-256")
                ParcelFileDescriptor.AutoCloseInputStream(input).use { source ->
                    ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(output.fileDescriptor)).use { destination ->
                        DocumentFiles.copyBounded(source, DigestOutputStream(destination, digest),
                            data.getLong("limit").coerceIn(1L, DocumentFiles.MAX_BYTES), validatePdf = true) {
                            signal.throwIfCanceled()
                            val fs = Os.fstatvfs(output.fileDescriptor)
                            if (fs.f_bavail * fs.f_frsize < DocumentStore.MIN_FREE_BYTES) throw StorageFull()
                        }
                        output.fileDescriptor.sync()
                    }
                }
                reply(Bundle().apply {
                    putString("name", DocumentFiles.safeName(name))
                    putString("digest", digest.digest().joinToString("") { "%02x".format(it) })
                })
            } finally { timer.removeCallbacks(cancel); signal.cancel() }
        }
    }
}
