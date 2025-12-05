package com.xalies.meshvault

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ModelEntity::class, FolderEntity::class], version = 5) // Bumped to 5 for thumbnailData
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "meshvault_database"
                )
                    .fallbackToDestructiveMigration() // Wipe DB on schema change
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}