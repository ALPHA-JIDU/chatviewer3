package com.jihad.chatviewer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

object MessageKind {
    const val NORMAL = 0
    const val CALL = 1
    const val UNSENT = 2
}

object MediaKind {
    const val PHOTO = 0
    const val VIDEO = 1
    const val AUDIO = 2
    const val GIF = 3
    const val STICKER = 4
    const val FILE = 5
}

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val title: String,
    val groupLabel: String,
    val participants: String,
    val isGroup: Boolean,
    val messageCount: Int,
    val firstMessageMs: Long,
    val lastMessageMs: Long,
    val lastPreview: String,
    val mediaBytes: Long
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["chatId", "timestampMs"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val uid: String,
    val chatId: String,
    val timestampMs: Long,
    val sender: String,
    val isMe: Boolean,
    val text: String,
    val kind: Int,
    val callDurationSec: Int,
    val shareLink: String
)

@Entity(tableName = "messages_fts")
@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class MessageFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val text: String
)

@Entity(
    tableName = "media",
    indices = [
        Index(value = ["messageUid"]),
        Index(value = ["chatId", "kind"])
    ]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageUid: String,
    val chatId: String,
    val kind: Int,
    val path: String,
    val timestampMs: Long,
    val sizeBytes: Long
)

@Entity(
    tableName = "reactions",
    indices = [Index(value = ["messageUid"])]
)
data class ReactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageUid: String,
    val reaction: String,
    val actor: String
)
