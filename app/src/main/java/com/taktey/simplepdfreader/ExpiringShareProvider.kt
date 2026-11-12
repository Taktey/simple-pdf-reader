package com.taktey.simplepdfreader

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

class ExpiringShareProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        val fd = super.openFile(uri, mode) ?: throw FileNotFoundException()
        try {
            if (Os.fstat(fd.fileDescriptor).st_mtime * 1000 < System.currentTimeMillis() - DocumentStore.SHARE_AGE)
                throw FileNotFoundException("Expired share")
            return fd
        } catch (error: Exception) { fd.close(); throw error }
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Read only")
}
