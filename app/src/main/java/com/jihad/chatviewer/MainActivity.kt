package com.jihad.chatviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile

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
    var message by remember { mutableStateOf("Nothing selected yet.") }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val names = uris.map {
                DocumentFile.fromSingleUri(context, it)?.name ?: "unknown"
            }
            message = "ZIP files selected: ${uris.size}\n" + names.joinToString("\n")
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
                // Not all folders allow this; fine for now.
            }
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "unknown"
            message = "Folder selected: $name"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Chat Viewer", style = MaterialTheme.typography.headlineMedium)
        Text("Import your Messenger export")
        Button(onClick = {
            zipPicker.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
        }) { Text("Import from ZIP files") }
        Button(onClick = { folderPicker.launch(null) }) { Text("Import from folder") }
        Text(message)
    }
}
