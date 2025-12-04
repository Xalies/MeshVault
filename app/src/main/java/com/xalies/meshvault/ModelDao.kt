package com.xalies.meshvault

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    // --- FOLDER OPERATIONS ---
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY name ASC")
    suspend fun getAllFoldersList(): List<FolderEntity>

    @Query("SELECT * FROM models ORDER BY dateAdded DESC")
    suspend fun getAllModels(): List<ModelEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FolderEntity)

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE name = :folderName")
    suspend fun deleteFolder(folderName: String)

    // --- MODEL OPERATIONS ---
    @Query("SELECT * FROM models WHERE folderName = :folderName ORDER BY dateAdded DESC")
    fun getModelsInFolder(folderName: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE folderName = :folderName")
    suspend fun getModelsInFolderList(folderName: String): List<ModelEntity>

    @Query("SELECT * FROM models WHERE folderName = :folderName OR folderName LIKE :folderPrefix")
    suspend fun getModelsInHierarchy(folderName: String, folderPrefix: String): List<ModelEntity>

    @Query("SELECT COUNT(*) FROM models WHERE folderName = :folderName")
    suspend fun getModelCount(folderName: String): Int

    @Query("SELECT COUNT(*) FROM models WHERE folderName = :folderName OR folderName LIKE :folderPrefix")
    suspend fun getModelCountInHierarchy(folderName: String, folderPrefix: String): Int

    @Insert
    suspend fun insertModel(model: ModelEntity)

    @Query("DELETE FROM models WHERE id = :modelId")
    suspend fun deleteModel(modelId: Int)

    @Query("DELETE FROM models WHERE folderName = :folderName")
    suspend fun deleteModelsInFolder(folderName: String)

    @Query("DELETE FROM models WHERE folderName = :folderName OR folderName LIKE :folderPrefix")
    suspend fun deleteModelsInHierarchy(folderName: String, folderPrefix: String)

    @Query("DELETE FROM folders WHERE name = :folderName OR name LIKE :folderPrefix")
    suspend fun deleteFolderHierarchy(folderName: String, folderPrefix: String)

    // --- BACKUP OPERATIONS ---
    @Query("SELECT * FROM models WHERE googleDriveId IS NULL")
    suspend fun getPendingUploads(): List<ModelEntity>

    @Query("UPDATE models SET googleDriveId = :driveId WHERE id = :modelId")
    suspend fun markAsUploaded(modelId: Int, driveId: String)
}