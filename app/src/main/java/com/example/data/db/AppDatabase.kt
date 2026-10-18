package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.db.dao.CategoryDao
import com.example.data.db.dao.CategoryChannelCrossRefDao
import com.example.data.db.dao.ChannelDao
import com.example.data.db.entity.Category
import com.example.data.db.entity.CategoryChannelCrossRef
import com.example.data.model.Channel

@Database(
    entities = [Category::class, Channel::class, CategoryChannelCrossRef::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun categoryChannelCrossRefDao(): CategoryChannelCrossRefDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v2: yedek yayın adresleri ve kalite etiketi. Kullanıcı kategorileri korunur.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN alternateUrls TEXT")
                db.execSQL("ALTER TABLE channels ADD COLUMN quality TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kapukayatv_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
