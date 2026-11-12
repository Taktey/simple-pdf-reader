package com.taktey.simplepdfreader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/** Fault injection fixtures exist only in debug APKs. */
class WatchdogTestService : BoundedWorkerService() {
    override fun execute(operation: Int, data: Bundle, reply: (Bundle) -> Unit) {
        Thread.sleep(60_000)
        reply(Bundle())
    }
}

class BlockedTestProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/pdf"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("blocked.pdf")) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        writer?.close()
        writer = pipe[1]
        return pipe[0]
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    companion object { @Volatile var writer: ParcelFileDescriptor? = null }
}
