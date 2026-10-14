package com.jihad.chatviewer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Query("SELECT * FROM chats ORDER BY lastMessageMs DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT COUNT(*) FROM chats")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<MessageEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun countForChat(chatId: String): Int
}

@Dao
interface MediaDao {
    @Insert
    suspend fun insertAll(items: List<MediaEntity>)
}

@Dao
interface ReactionDao {
    @Insert
    suspend fun insertAll(items: List<ReactionEntity>)
}
