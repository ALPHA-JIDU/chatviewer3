package com.jihad.chatviewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.jihad.chatviewer.importer.ExportScanner
import com.jihad.chatviewer.importer.ImportRequest
import com.jihad.chatviewer.importer.ImportRequestStore
import com.jihad.chatviewer.importer.ImportSource
import com.jihad.chatviewer.importer.ImportUiState
import com.jihad.chatviewer.importer.ImportWorker
import com.jihad.chatviewer.importer.ScanResult
import com.jihad.chatviewer.ui.ChatPickerScreen
import com.jihad.chatviewer.ui.ImportScreen
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

enum class Screen { START, PICKER, IMPORT }

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val container = (context.applicationContext as ChatViewerApp).container
    val state by container.importState.collectAsState()
    var screen by remember {
        mutableStateOf(if (container.importState.value.running) Screen.IMPORT else Screen.START)
    }
    var pendingIds by remember { mutableStateOf<Set<String>?>(null) }

    fun beginImport(ids: Set<String>) {
        val source = container.lastSource ?: return
        val scan = container.lastScan ?: return
        ImportRequestStore.save(context, ImportRequest(source, ids, scan.myName))
        val total = scan.chats.filter { it.chatId in ids }.sumOf { it.messageCount.toLong() }
        container.importState.value =
            ImportUiState(running = true, text = "Starting...", total = total)
        val work = OneTimeWorkRequestBuilder<ImportWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("import", ExistingWorkPolicy.KEEP, work)
        screen = Screen.IMPORT
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val ids = pendingIds
        pendingIds = null
        if (ids != null) beginImport(ids)
    }

    val scan = container.lastScan

    when {
        screen == Screen.IMPORT -> {
            BackHandler(enabled = !state.running) { screen = Screen.START }
            ImportScreen(
                state = state,
                onCancel = { WorkManager.getInstance(context).cancelUniqueWork("import") },
                onDone = { screen = Screen.START }
            )
        }
        screen == Screen.PICKER && scan != null -> {
            BackHandler { screen = Screen.START }
            ChatPickerScreen(
                scan = scan,
                initialSelected = container.selectedChatIds,
                onBack = { screen = Screen.START },
                onConfirm = { ids ->
                    container.selectedChatIds = ids
                    val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        pendingIds = ids
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        beginImport(ids)
                    }
                }
            )
        }
        else -> {
            ImportStartScreen(onChooseChats = { screen = Screen.PICKER })
        }
    }
}

@Composable
fun ImportStartScreen(onChooseChats: () -> Unit) {
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
        if (uris.isNotEmpty()) {
            for (u in uris) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // fine, access still works while the app runs
                }
            }
            startScan(ImportSource.Zips(uris)) { it.scanZips(uris) }
        }
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
        }
    }
}
