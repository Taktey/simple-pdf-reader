package com.taktey.simplepdfreader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val reader: ReaderViewModel by viewModels()
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(reader::open)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) PdfIntents.incoming(intent)?.let(reader::open)
        setContent {
            ReaderTheme {
                ReaderApp(
                    state = reader.state.collectAsStateWithLifecycle().value,
                    recentDocuments = reader.recent.collectAsStateWithLifecycle().value,
                    onRecentOpen = reader::openRecent,
                    onOpen = { pickPdf.launch(arrayOf("application/pdf")) },
                    onClose = reader::closeDocument,
                    onPosition = reader::rememberPosition,
                    onShare = { session ->
                        try {
                            startActivity(Intent.createChooser(PdfIntents.share(this, session.file), getString(R.string.share)))
                        } catch (_: Exception) {
                            Toast.makeText(this, R.string.error_share, Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PdfIntents.incoming(intent)?.let(reader::open)
    }
}
