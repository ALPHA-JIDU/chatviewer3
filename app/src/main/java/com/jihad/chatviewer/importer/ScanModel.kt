package com.jihad.chatviewer.importer

import java.io.InputStream

internal val MESSAGE_FILE = Regex("^message_\\d+\\.json$")
internal val TRAILING_NUMBER = Regex("_(\\d+)$")

internal fun chatIdOf(folder: String): String =
    TRAILING_NUMBER.find(folder)?.groupValues?.get(1) ?: folder

data class ScannedChat(
    val chatId: String,
    val folderName: String,
    val title: String,
    val groupLabel: String,
    val participantCount: Int,
    val messageCount: Int,
    val firstMessageMs: Long,
    val lastMessageMs: Long,
    val mediaBytes: Long
)

data class ScanResult(
    val chats: List<ScannedChat>,
    val stickersBytes: Long,
    val unreadable: List<String>,
    val myName: String = ""
)

class ScanAccumulator {
    private class Builder(val chatId: String, val folderName: String) {
        var title = ""
        val groups = LinkedHashSet<String>()
        val names = LinkedHashSet<String>()
        var participants = 0
        var messages = 0
        var firstMs = Long.MAX_VALUE
        var lastMs = 0L
        var mediaBytes = 0L
    }

    private val builders = LinkedHashMap<String, Builder>()
    private var stickersBytes = 0L
    private val unreadable = ArrayList<String>()

    /** rel = path below the "messages" folder, e.g. [inbox, name_123, photos, a.jpg] */
    fun onFile(rel: List<String>, size: Long, open: () -> InputStream) {
        if (rel.size < 2) return
        val group = rel[0]
        if (group == "stickers_used") {
            stickersBytes += maxOf(size, 0L)
            return
        }
        if (rel.size < 3) return

        val folder = rel[1]
        val id = chatIdOf(folder)
        val b = builders.getOrPut(id) { Builder(id, folder) }
        b.groups.add(group)

        if (rel.size == 3 && MESSAGE_FILE.matches(rel[2])) {
            try {
                val info = open().use { ChatFileParser.parse(it) }
                if (b.title.isEmpty()) b.title = info.title
                b.participants = maxOf(b.participants, info.participants)
                b.names.addAll(info.participantNames)
                b.messages += info.messageCount
                if (info.messageCount > 0) {
                    b.firstMs = minOf(b.firstMs, info.firstMs)
                    b.lastMs = maxOf(b.lastMs, info.lastMs)
                }
            } catch (e: Exception) {
                unreadable.add("$folder/${rel[2]}")
            }
        } else if (rel.size > 3) {
            b.mediaBytes += maxOf(size, 0L)
        }
    }

    fun result(): ScanResult {
        val chats = builders.values.map { b ->
            ScannedChat(
                chatId = b.chatId,
                folderName = b.folderName,
                title = b.title.ifEmpty { b.folderName },
                groupLabel = b.groups.joinToString(", "),
                participantCount = b.participants,
                messageCount = b.messages,
                firstMessageMs = if (b.firstMs == Long.MAX_VALUE) 0L else b.firstMs,
                lastMessageMs = b.lastMs,
                mediaBytes = b.mediaBytes
            )
        }.sortedByDescending { it.lastMessageMs }

        val tally = HashMap<String, Int>()
        for (b in builders.values) {
            for (n in b.names) tally[n] = (tally[n] ?: 0) + 1
        }
        val myName = tally.entries.maxByOrNull { it.value }?.key ?: ""

        return ScanResult(chats, stickersBytes, unreadable.toList(), myName)
    }
}
