package com.jihad.chatviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.jihad.chatviewer.importer.ExportScanner
import com.jihad.chatviewer.importer.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ImportStartScreen()
                }
            }
        }
    }
}

@Composable
fun ImportStartScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as ChatViewerApp).container
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var status by remember { mutableStateOf("Choose your export to scan.") }
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(scanning) {
        view.keepScreenOn = scanning
        onDispose { view.keepScreenOn = false }
    }

    fun startScan(work: (ExportScanner) -> ScanResult) {
        if (scanning) return
        scanning = true
        result = null
        status = "Starting..."
        job = scope.launch(Dispatchers.IO) {
            try {
                val scanner = ExportScanner(
                    context.applicationContext,
                    { isActive },
                    { status = it }
                )
                val r = work(scanner)
                container.lastScan = r
                result = r
                status = "Scan finished."
            } catch (e: CancellationException) {
                status = "Scan cancelled."
            } catch (e: Exception) {
                status = "Scan failed: ${e.message}"
            } finally {
                scanning = false
            }
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) startScan { it.scanZips(uris) }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // fine for now
            }
            startScan { it.scanFolder(uri) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Chat Viewer", style = MaterialTheme.typography.headlineMedium)
        Text("Import your Messenger export")
        Button(enabled = !scanning, onClick = {
            zipPicker.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
        }) { Text("Import from ZIP files") }
        Button(enabled = !scanning, onClick = { folderPicker.launch(null) }) {
            Text("Import from folder")
        }
        if (scanning) {
            OutlinedButton(onClick = { job?.cancel() }) { Text("Cancel scan") }
        }
        Text(status)

        val r = result
        if (r != null) {
            val totalMessages = r.chats.sumOf { it.messageCount.toLong() }
            val totalMedia = r.chats.sumOf { it.mediaBytes }
            Text(
                "Chats found: ${r.chats.size}\n" +
                    "Messages: $totalMessages\n" +
                    "Chat media: ${Formatter.formatShortFileSize(context, totalMedia)}\n" +
                    "Shared stickers: ${Formatter.formatShortFileSize(context, r.stickersBytes)}\n" +
                    "Unreadable files: ${r.unreadable.size}"
            )
            if (r.unreadable.isNotEmpty()) {
                Text("Unreadable (first 5):\n" + r.unreadable.take(5).joinToString("\n"))
            }
            Text("Latest 10 chats:")
            r.chats.take(10).forEach { c ->
                Text(
                    "${c.title}\n  ${c.messageCount} messages, " +
                        Formatter.formatShortFileSize(context, c.mediaBytes) +
                        ", ${c.groupLabel}"
                )
            }
        }
    }
}
