package com.jihad.chatviewer.importer

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class ChatFileInfo(
    val title: String,
    val participants: Int,
    val messageCount: Int,
    val firstMs: Long,
    val lastMs: Long
)

object ChatFileParser {
    fun parse(input: InputStream): ChatFileInfo {
        val reader = JsonReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024))
        var title = ""
        var participants = 0
        var count = 0
        var firstMs = Long.MAX_VALUE
        var lastMs = 0L

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "title" -> {
                    if (reader.peek() == JsonToken.STRING) {
                        title = TextFix.fix(reader.nextString())
                    } else {
                        reader.skipValue()
                    }
                }
                "participants" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.skipValue()
                            participants++
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                "messages" -> {
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                                reader.skipValue()
                                continue
                            }
                            reader.beginObject()
                            var ts = -1L
                            while (reader.hasNext()) {
                                val name = reader.nextName()
                                if (name == "timestamp_ms" && reader.peek() == JsonToken.NUMBER) {
                                    ts = reader.nextLong()
                                } else {
                                    reader.skipValue()
                                }
                            }
                            reader.endObject()
                            count++
                            if (ts >= 0) {
                                if (ts < firstMs) firstMs = ts
                                if (ts > lastMs) lastMs = ts
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
        if (firstMs == Long.MAX_VALUE) firstMs = 0L
        return ChatFileInfo(title, participants, count, firstMs, lastMs)
    }
}
