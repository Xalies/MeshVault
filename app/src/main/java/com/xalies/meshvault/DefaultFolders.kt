package com.xalies.meshvault

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ensures the default starter folders exist so download dialogs always have choices on first run.
 */
suspend fun ensureDefaultFoldersInitialized(context: Context, dao: ModelDao) {
    withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
        val defaultsInitialized = preferences.getBoolean("defaults_initialized", false)
        if (defaultsInitialized) return@withContext

        val defaults = listOf(
            FolderEntity("Household", color = FOLDER_COLORS.random(), iconName = "Home"),
            FolderEntity("Games", color = FOLDER_COLORS.random(), iconName = "Game"),
            FolderEntity("Gadgets", color = FOLDER_COLORS.random(), iconName = "Build"),
            FolderEntity("Cosplay", color = FOLDER_COLORS.random(), iconName = "Face"),
            // Subfolders for Cosplay
            FolderEntity("Cosplay/Masks", color = FOLDER_COLORS.random(), iconName = "Face"),
            FolderEntity("Cosplay/Props", color = FOLDER_COLORS.random(), iconName = "Star")
        )

        defaults.forEach { folder ->
            if (dao.getFolderCount(folder.name) == 0) {
                dao.insertFolder(folder)
            }
        }

        preferences.edit().putBoolean("defaults_initialized", true).apply()
    }
}
