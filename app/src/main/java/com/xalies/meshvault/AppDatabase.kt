package com.xalies.meshvault

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ModelEntity::class, FolderEntity::class], version = 2) // Added FolderEntity, Version 2
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao

    companion object {
        // Singleton pattern to prevent multiple database instances
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "meshvault_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}