package com.jihad.chatviewer.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jihad.chatviewer.importer.ScanResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatPickerScreen(
    scan: ScanResult,
    initialSelected: Set<String>,
    onBack: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialSelected) }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }

    val shown = remember(query, scan) {
        val q = query.trim()
        if (q.isEmpty()) scan.chats
        else scan.chats.filter { it.title.contains(q, ignoreCase = true) }
    }
    val selectedChats = scan.chats.filter { it.chatId in selected }
    val selectedMessages = selectedChats.sumOf { it.messageCount.toLong() }
    val selectedMedia = selectedChats.sumOf { it.mediaBytes }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Choose chats", style = MaterialTheme.typography.titleLarge)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search by name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = {
                selected = selected + shown.map { it.chatId }
            }) { Text("Select shown") }
            OutlinedButton(onClick = {
                selected = selected - shown.map { it.chatId }.toSet()
            }) { Text("Clear shown") }
        }

        Text(
            "${selected.size} of ${scan.chats.size} chats selected\n" +
                "$selectedMessages messages, " +
                Formatter.formatShortFileSize(context, selectedMedia) + " of media",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(shown, key = { it.chatId }) { chat ->
                val checked = chat.chatId in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (checked) selected - chat.chatId else selected + chat.chatId
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            chat.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val kind = if (chat.participantCount > 2) "Group" else "Chat"
                        val last = if (chat.lastMessageMs > 0) dateFormat.format(Date(chat.lastMessageMs)) else "-"
                        Text(
                            "${chat.messageCount} messages · " +
                                Formatter.formatShortFileSize(context, chat.mediaBytes) +
                                " · $kind · ${chat.groupLabel} · last $last",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                HorizontalDivider()
            }
        }

        Button(
            enabled = selected.isNotEmpty(),
            onClick = { onConfirm(selected) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) { Text("Import ${selected.size} selected chats") }
    }
}
