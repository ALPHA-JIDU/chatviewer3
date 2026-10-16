package com.jihad.chatviewer.importer

import com.jihad.chatviewer.data.AppDatabase
import com.jihad.chatviewer.data.ChatEntity
import com.jihad.chatviewer.data.MediaEntity
import com.jihad.chatviewer.data.MediaKind
import com.jihad.chatviewer.data.MessageKind
import com.jihad.chatviewer.data.ReactionEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

class ImportSummary(
    val chats: Int,
    val messagesRead: Long,
    val duplicates: Long,
    val mediaCopied: Int,
    val mediaSkipped: Int,
    val unreadable: List<String>
)

class ExportImporter(
    private val db: AppDatabase,
    private val walker: ExportWalker,
    private val request: ImportRequest,
    private val mediaRoot: File,
    private val isActive: () -> Boolean,
    private val onProgress: (String, Long) -> Unit
) {
    private class ChatAcc(val chatId: String, val folderName: String) {
        var title = ""
        val groups = LinkedHashSet<String>()
        val names = LinkedHashSet<String>()
        var firstMs = Long.MAX_VALUE
        var lastMs = 0L
        var lastPreview = ""
        var mediaBytes = 0L
    }

    private val chats = LinkedHashMap<String, ChatAcc>()
    private val unreadable = ArrayList<String>()
    private var read = 0L
    private var inserted = 0L
    private var doneKb = 0L
    private var mediaCopied = 0
    private var mediaSkipped = 0

    private fun progress(text: String) {
        onProgress(text, read + doneKb)
    }

    fun run(): ImportSummary {
        try {
            walker.walk(request.source) { rel, size, open -> handle(rel, size, open) }
        } finally {
            runBlocking {
                for (c in chats.values) finishChat(c)
            }
        }
        return ImportSummary(
            chats.size, read, read - inserted, mediaCopied, mediaSkipped, unreadable.toList()
        )
    }

    private fun handle(rel: List<String>, size: Long, open: () -> InputStream) {
        if (rel.size < 2) return
        if (rel[0] == "stickers_used") {
            copyMedia(rel, size, open, null)
            return
        }
        if (rel.size < 3) return
        val folder = rel[1]
        val id = chatIdOf(folder)
        if (id !in request.chatIds) return

        if (rel.size == 3 && MESSAGE_FILE.matches(rel[2])) {
            readMessages(rel, id, folder, open)
        } else if (rel.size > 3) {
            val acc = chats.getOrPut(id) { ChatAcc(id, folder) }
            acc.groups.add(rel[0])
            copyMedia(rel, size, open, acc)
        }
    }

    // ---------- messages ----------

    private fun readMessages(rel: List<String>, id: String, folder: String, open: () -> InputStream) {
        progress("Reading $folder")
        val acc = chats.getOrPut(id) { ChatAcc(id, folder) }
        acc.groups.add(rel[0])
        val batch = ArrayList<ParsedMessage>()
        try {
            val header = open().use { input ->
                MessageParser.parse(input, id, request.myName) { pm ->
                    val ts = pm.message.timestampMs
                    if (ts > 0) {
                        if (ts < acc.firstMs) acc.firstMs = ts
                        if (ts >= acc.lastMs) {
                            acc.lastMs = ts
                            acc.lastPreview = previewOf(pm)
                        }
                    }
                    batch.add(pm)
                    read++
                    if (batch.size >= 500) {
                        flush(batch)
                        progress("$read messages read")
                        if (!isActive()) throw CancellationException("Cancelled")
                    }
                }
            }
            flush(batch)
            if (acc.title.isEmpty()) acc.title = header.title
            acc.names.addAll(header.participantNames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                flush(batch)
            } catch (e2: Exception) {
                // ignore, the file is listed as unreadable below
            }
            unreadable.add("$folder/${rel[2]}")
        }
    }

    private fun flush(batch: MutableList<ParsedMessage>) {
        if (batch.isEmpty()) return
        runBlocking {
            val ids = db.messageDao().insertAll(batch.map { it.message })
            val media = ArrayList<MediaEntity>()
            val reactions = ArrayList<ReactionEntity>()
            for (i in batch.indices) {
                if (ids[i] != -1L) {
                    inserted++
                    media.addAll(batch[i].media)
                    reactions.addAll(batch[i].reactions)
                }
            }
            if (media.isNotEmpty()) db.mediaDao().insertAll(media)
            if (reactions.isNotEmpty()) db.reactionDao().insertAll(reactions)
        }
        batch.clear()
    }

    // ---------- media ----------

    private fun copyMedia(rel: List<String>, size: Long, open: () -> InputStream, acc: ChatAcc?) {
        val label = rel.joinToString("/")
        if (rel.any { it.isEmpty() || it == "." || it == ".." || it.contains('\\') }) {
            unreadable.add(label)
            return
        }
        val dest = File(mediaRoot, "messages/$label")
        if (dest.isFile && (size < 0 || dest.length() == size)) {
            mediaSkipped++
            if (acc != null) acc.mediaBytes += dest.length()
            doneKb += dest.length() / 1024L
            return
        }
        val parent = dest.parentFile
        if (parent == null) {
            unreadable.add(label)
            return
        }
        val where = if (rel[0] == "stickers_used") "stickers" else rel[1]
        val tmp = File(parent, ".copying.part")
        var copied = 0L
        var sinceReport = 0L
        try {
            parent.mkdirs()
            open().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        sinceReport += n
                        if (sinceReport >= 1_048_576L) {
                            doneKb += sinceReport / 1024L
                            sinceReport = 0L
                            progress("Copying media: $where")
                            if (!isActive()) throw CancellationException("Cancelled")
                        }
                    }
                }
            }
            doneKb += sinceReport / 1024L
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Could not save $label")
            mediaCopied++
            if (acc != null) acc.mediaBytes += copied
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            unreadable.add(label)
        }
    }

    // ---------- chat entries ----------

    private suspend fun finishChat(c: ChatAcc) {
        val count = db.messageDao().countForChat(c.chatId)
        db.chatDao().upsert(
            ChatEntity(
                chatId = c.chatId,
                title = c.title.ifEmpty { c.folderName },
                groupLabel = c.groups.joinToString(", "),
                participants = c.names.joinToString("\n"),
                isGroup = c.names.size > 2,
                messageCount = count,
                firstMessageMs = if (c.firstMs == Long.MAX_VALUE) 0L else c.firstMs,
                lastMessageMs = c.lastMs,
                lastPreview = c.lastPreview,
                mediaBytes = c.mediaBytes
            )
        )
    }

    private fun previewOf(pm: ParsedMessage): String {
        val m = pm.message
        if (m.kind == MessageKind.UNSENT) return "Message unsent"
        if (m.kind == MessageKind.CALL) return "Call"
        if (m.text.isNotEmpty()) return m.text.take(120)
        val first = pm.media.firstOrNull()
        if (first != null) {
            return when (first.kind) {
                MediaKind.PHOTO -> "Photo"
                MediaKind.VIDEO -> "Video"
                MediaKind.AUDIO -> "Voice message"
                MediaKind.GIF -> "GIF"
                MediaKind.STICKER -> "Sticker"
                else -> "File"
            }
        }
        if (m.shareLink.isNotEmpty()) return "Link"
        return ""
    }
}
