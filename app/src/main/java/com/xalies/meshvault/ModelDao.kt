package com.xalies.meshvault

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    // --- FOLDER OPERATIONS ---
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE name = :folderName")
    suspend fun deleteFolder(folderName: String)

    // --- MODEL OPERATIONS ---
    @Query("SELECT * FROM models WHERE folderName = :folderName ORDER BY dateAdded DESC")
    fun getModelsInFolder(folderName: String): Flow<List<ModelEntity>>

    // NEW: Get list for the Wifi Server (Suspend function)
    @Query("SELECT * FROM models WHERE folderName = :folderName")
    suspend fun getModelsInFolderList(folderName: String): List<ModelEntity>

    @Query("SELECT COUNT(*) FROM models WHERE folderName = :folderName")
    suspend fun getModelCount(folderName: String): Int

    @Insert
    suspend fun insertModel(model: ModelEntity)

    @Query("DELETE FROM models WHERE id = :modelId")
    suspend fun deleteModel(modelId: Int)

    @Query("DELETE FROM models WHERE folderName = :folderName")
    suspend fun deleteModelsInFolder(folderName: String)
}