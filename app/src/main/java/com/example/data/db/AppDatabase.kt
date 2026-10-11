package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.db.dao.CategoryDao
import com.example.data.db.dao.CategoryChannelCrossRefDao
import com.example.data.db.dao.ChannelDao
import com.example.data.db.entity.Category
import com.example.data.db.entity.CategoryChannelCrossRef
import com.example.data.model.Channel

@Database(
    entities = [Category::class, Channel::class, CategoryChannelCrossRef::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun categoryChannelCrossRefDao(): CategoryChannelCrossRefDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kapukayatv_db"
                ).fallbackToDestructiveMigration(true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
