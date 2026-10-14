package com.jihad.chatviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import com.jihad.chatviewer.importer.ImportSource
import com.jihad.chatviewer.importer.ScanResult
import com.jihad.chatviewer.ui.ChatPickerScreen
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
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val container = (context.applicationContext as ChatViewerApp).container
    var showPicker by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val scan = container.lastScan

    if (showPicker && scan != null) {
        BackHandler { showPicker = false }
        ChatPickerScreen(
            scan = scan,
            initialSelected = container.selectedChatIds,
            onBack = { showPicker = false },
            onConfirm = { ids ->
                container.selectedChatIds = ids
                notice = "${ids.size} chats selected. Importing comes in the next batch."
                showPicker = false
            }
        )
    } else {
        ImportStartScreen(
            notice = notice,
            onChooseChats = { showPicker = true }
        )
    }
}

@Composable
fun ImportStartScreen(notice: String, onChooseChats: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as ChatViewerApp).container
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var status by remember {
        mutableStateOf(
            if (container.lastScan != null) "Scan finished." else "Choose your export to scan."
        )
    }
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ScanResult?>(container.lastScan) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(scanning) {
        view.keepScreenOn = scanning
        onDispose { view.keepScreenOn = false }
    }

    fun startScan(source: ImportSource, work: (ExportScanner) -> ScanResult) {
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
                container.lastSource = source
                container.selectedChatIds = emptySet()
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
        if (uris.isNotEmpty()) startScan(ImportSource.Zips(uris)) { it.scanZips(uris) }
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
            startScan(ImportSource.Folder(uri)) { it.scanFolder(uri) }
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
        if (r != null && !scanning) {
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
            Button(onClick = onChooseChats) { Text("Choose chats to import") }
            if (notice.isNotEmpty()) Text(notice)
        }
    }
}
