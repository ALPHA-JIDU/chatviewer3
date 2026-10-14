package com.jihad.chatviewer.importer

import android.util.JsonReader
import android.util.JsonToken
import com.jihad.chatviewer.data.MediaEntity
import com.jihad.chatviewer.data.MediaKind
import com.jihad.chatviewer.data.MessageEntity
import com.jihad.chatviewer.data.MessageKind
import com.jihad.chatviewer.data.ReactionEntity
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest

class ParsedMessage(
    val message: MessageEntity,
    val media: List<MediaEntity>,
    val reactions: List<ReactionEntity>
)

class ChatFileHeader {
    var title: String = ""
    val participantNames = ArrayList<String>()
}

object MessageParser {

    fun parse(
        input: InputStream,
        chatId: String,
        myName: String,
        onMessage: (ParsedMessage) -> Unit
    ): ChatFileHeader {
        val reader = JsonReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024))
        val header = ChatFileHeader()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "title" -> header.title = readFixed(reader)
                "participants" -> readParticipants(reader, header.participantNames)
                "messages" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                onMessage(readMessage(reader, chatId, myName))
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return header
    }

    private fun readMessage(r: JsonReader, chatId: String, myName: String): ParsedMessage {
        var sender = ""
        var ts = 0L
        var text = ""
        var share = ""
        var unsent = false
        var hasCall = false
        var callSec = 0
        val mediaRaw = ArrayList<Pair<Int, String>>()
        val reactionsRaw = ArrayList<Pair<String, String>>()

        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "sender_name" -> sender = readFixed(r)
                "timestamp_ms" -> ts = readLong(r)
                "content" -> text = readFixed(r)
                "is_unsent" -> unsent = readBoolean(r)
                "call_duration" -> {
                    hasCall = true
                    callSec = readLong(r).toInt()
                }
                "reactions" -> readReactions(r, reactionsRaw)
                "photos" -> readMediaArray(r, MediaKind.PHOTO, mediaRaw)
                "videos" -> readMediaArray(r, MediaKind.VIDEO, mediaRaw)
                "audio_files" -> readMediaArray(r, MediaKind.AUDIO, mediaRaw)
                "gifs" -> readMediaArray(r, MediaKind.GIF, mediaRaw)
                "files" -> readMediaArray(r, MediaKind.FILE, mediaRaw)
                "sticker" -> readOneMedia(r, MediaKind.STICKER, mediaRaw)
                "share" -> share = readShareLink(r)
                else -> r.skipValue()
            }
        }
        r.endObject()

        val firstMedia = mediaRaw.firstOrNull()?.second ?: ""
        val uid = makeUid(chatId, ts, sender, text, firstMedia, share)
        val kind = when {
            unsent -> MessageKind.UNSENT
            hasCall -> MessageKind.CALL
            else -> MessageKind.NORMAL
        }
        val message = MessageEntity(
            uid = uid,
            chatId = chatId,
            timestampMs = ts,
            sender = sender,
            isMe = myName.isNotEmpty() && sender == myName,
            text = text,
            kind = kind,
            callDurationSec = callSec,
            shareLink = share
        )
        val media = mediaRaw.map {
            MediaEntity(
                messageUid = uid,
                chatId = chatId,
                kind = it.first,
                path = it.second,
                timestampMs = ts,
                sizeBytes = 0L
            )
        }
        val reactions = reactionsRaw.map {
            ReactionEntity(messageUid = uid, reaction = it.first, actor = it.second)
        }
        return ParsedMessage(message, media, reactions)
    }

    private fun readParticipants(r: JsonReader, out: MutableList<String>) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return
        }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                r.skipValue()
                continue
            }
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "name") out.add(readFixed(r)) else r.skipValue()
            }
            r.endObject()
        }
        r.endArray()
    }

    private fun readReactions(r: JsonReader, out: MutableList<Pair<String, String>>) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return
        }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                r.skipValue()
                continue
            }
            var reaction = ""
            var actor = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "reaction" -> reaction = readFixed(r)
                    "actor" -> actor = readFixed(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            out.add(Pair(reaction, actor))
        }
        r.endArray()
    }

    private fun readMediaArray(r: JsonReader, kind: Int, out: MutableList<Pair<Int, String>>) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return
        }
        r.beginArray()
        while (r.hasNext()) readOneMedia(r, kind, out)
        r.endArray()
    }

    private fun readOneMedia(r: JsonReader, kind: Int, out: MutableList<Pair<Int, String>>) {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return
        }
        var uri = ""
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "uri") uri = readString(r) else r.skipValue()
        }
        r.endObject()
        if (uri.isNotEmpty()) out.add(Pair(kind, mediaPath(uri)))
    }

    private fun readShareLink(r: JsonReader): String {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return ""
        }
        var link = ""
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "link") link = readString(r) else r.skipValue()
        }
        r.endObject()
        return link
    }

    /** Keeps the part of the path that starts at the "messages" folder. */
    private fun mediaPath(uri: String): String {
        val segs = uri.split('/').filter { it.isNotEmpty() }
        val i = segs.indexOf("messages")
        return if (i >= 0) segs.drop(i).joinToString("/") else uri
    }

    private fun readString(r: JsonReader): String {
        if (r.peek() == JsonToken.STRING) return r.nextString()
        r.skipValue()
        return ""
    }

    private fun readFixed(r: JsonReader): String = TextFix.fix(readString(r))

    private fun readLong(r: JsonReader): Long {
        if (r.peek() == JsonToken.NUMBER) return r.nextDouble().toLong()
        r.skipValue()
        return 0L
    }

    private fun readBoolean(r: JsonReader): Boolean {
        if (r.peek() == JsonToken.BOOLEAN) return r.nextBoolean()
        r.skipValue()
        return false
    }

    private const val HEX = "0123456789abcdef"

    private fun makeUid(
        chatId: String,
        ts: Long,
        sender: String,
        text: String,
        firstMedia: String,
        share: String
    ): String {
        val raw = "$chatId|$ts|$sender|$text|$firstMedia|$share"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
