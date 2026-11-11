package com.taktey.simplepdfreader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReaderState {
    data object Empty : ReaderState
    data object Loading : ReaderState
    data class Ready(val session: PdfSource, val initialPage: Int, val initialOffset: Int) : ReaderState
    data class Error(val message: Int) : ReaderState
}

class ReaderViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val store = DocumentStore(application)
    private val mutableState = MutableStateFlow<ReaderState>(ReaderState.Empty)
    val state = mutableState.asStateFlow()
    private var openJob: Job? = null
    private var current: PdfSession? = null

    init {
        saved.get<String>("document")?.let { path -> load(restoring = true) { store.restore(path) } }
    }

    fun open(uri: Uri) = load(restoring = false) { store.import(uri) }

    private fun load(restoring: Boolean, source: suspend () -> File) {
        openJob?.cancel()
        val previous = current
        current = null
        mutableState.value = ReaderState.Loading
        val page = if (restoring) saved.get<Int>("page") ?: 0 else 0
        val offset = if (restoring) saved.get<Int>("offset") ?: 0 else 0
        saved.remove<String>("document")
        openJob = viewModelScope.launch {
            var pending: PdfSession? = null
            try {
                withContext(Dispatchers.IO) {
                    previous?.close()
                    val file = source()
                    try { pending = PdfSession(file) }
                    catch (error: Exception) {
                        if (!restoring) file.parentFile?.deleteRecursively()
                        throw error
                    }
                    store.cleanOldCopies(file)
                }
                val session = requireNotNull(pending)
                saved["document"] = store.relativePath(session.file)
                saved["page"] = page.coerceIn(0, session.pageCount - 1)
                saved["offset"] = offset
                current = session
                mutableState.value = ReaderState.Ready(session, saved["page"] ?: 0, offset)
                pending = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is DocumentTooLarge -> R.string.error_large
                    is DocumentAccessDenied, is FileNotFoundException -> R.string.error_access
                    is SecurityException -> R.string.error_password
                    is InvalidDocument, is IllegalArgumentException -> R.string.error_invalid
                    is IOException -> R.string.error_io
                    else -> R.string.error_invalid
                }
                mutableState.value = ReaderState.Error(message)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    pending?.close()
                    previous?.close()
                }
            }
        }
    }

    fun rememberPosition(page: Int, offset: Int) {
        if (current == null) return
        saved["page"] = page
        saved["offset"] = offset
    }

    fun closeDocument() {
        openJob?.cancel()
        val old = current
        current = null
        saved.remove<String>("document")
        saved.remove<Int>("page")
        saved.remove<Int>("offset")
        mutableState.value = ReaderState.Empty
        viewModelScope.launch(Dispatchers.IO) { old?.close() }
    }

    override fun onCleared() { current?.close() }
}
