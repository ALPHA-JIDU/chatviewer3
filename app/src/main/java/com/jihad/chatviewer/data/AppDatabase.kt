package com.jihad.chatviewer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        MessageFts::class,
        MediaEntity::class,
        ReactionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun mediaDao(): MediaDao
    abstract fun reactionDao(): ReactionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "chatviewer.db").build()
    }
}
