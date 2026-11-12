package com.taktey.simplepdfreader

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.view.WindowManager
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
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
        val privacy = getSharedPreferences("privacy", MODE_PRIVATE)
        if (privacy.getBoolean("protectScreen", false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        if (savedInstanceState == null) PdfIntents.incoming(intent)?.let(reader::open)
        setContent {
            val settings = remember { getSharedPreferences("privacy", MODE_PRIVATE) }
            var protectScreen by remember { mutableStateOf(settings.getBoolean("protectScreen", false)) }
            val scope = rememberCoroutineScope()
            var sharing by remember { mutableStateOf(false) }
            DisposableEffect(protectScreen) {
                if (protectScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose { }
            }
            ReaderTheme {
                ReaderApp(
                    state = reader.state.collectAsStateWithLifecycle().value,
                    recentDocuments = reader.recent.collectAsStateWithLifecycle().value,
                    onRecentOpen = reader::openRecent,
                    onRecentDelete = reader::deleteRecent,
                    onRecentClear = reader::clearRecent,
                    protectScreen = protectScreen,
                    onProtectionChange = {
                        protectScreen = it
                        settings.edit().putBoolean("protectScreen", it).apply()
                    },
                    onOpen = { pickPdf.launch(arrayOf("application/pdf")) },
                    onClose = reader::closeDocument,
                    onPosition = reader::rememberPosition,
                    onShare = { session ->
                        if (!sharing) scope.launch {
                            sharing = true
                            try {
                                val sharedCopy = reader.shareFile(session)
                                startActivity(Intent.createChooser(PdfIntents.share(this@MainActivity, sharedCopy), getString(R.string.share)))
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                Toast.makeText(this@MainActivity, R.string.error_share, Toast.LENGTH_LONG).show()
                            } finally { sharing = false }
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
