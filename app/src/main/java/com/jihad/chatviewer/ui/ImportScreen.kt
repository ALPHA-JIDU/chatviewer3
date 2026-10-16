package com.jihad.chatviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.jihad.chatviewer.importer.ImportUiState

@Composable
fun ImportScreen(
    state: ImportUiState,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Importing chats", style = MaterialTheme.typography.headlineMedium)

        if (state.running) {
            if (state.total > 0) {
                val fraction = (state.done.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("${(fraction * 100).toInt()}%")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(state.text)
            Text("You can leave the app. The import continues in the background and shows a notification.")
            OutlinedButton(onClick = onCancel) { Text("Cancel import") }
        } else if (state.finished) {
            when {
                state.cancelled -> Text(
                    "Import cancelled.\nMessages and media already saved are kept. " +
                        "You can import again later; anything already saved is skipped."
                )
                state.failed.isNotEmpty() -> Text("Import failed:\n${state.failed}")
                else -> {
                    Text("Import finished.")
                    Text(state.resultText)
                    if (state.unreadable.isNotEmpty()) {
                        Text("Could not read (first 5):\n" + state.unreadable.take(5).joinToString("\n"))
                    }
                }
            }
            Button(onClick = onDone) { Text("Done") }
        } else {
            Text("Preparing...")
        }
    }
}
