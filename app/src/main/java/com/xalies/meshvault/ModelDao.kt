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

    // Updated: Only show non-deleted models
    @Query("SELECT * FROM models WHERE isDeleted = 0 ORDER BY dateAdded DESC")
    suspend fun getAllModels(): List<ModelEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: FolderEntity)

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE name = :folderName")
    suspend fun deleteFolder(folderName: String)

    // --- MODEL OPERATIONS ---
    // Updated: Only show non-deleted models
    @Query("SELECT * FROM models WHERE folderName = :folderName AND isDeleted = 0 ORDER BY dateAdded DESC")
    fun getModelsInFolder(folderName: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :modelId LIMIT 1")
    fun getModelById(modelId: Int): Flow<ModelEntity?>

    @Query("SELECT * FROM models WHERE folderName = :folderName AND isDeleted = 0")
    suspend fun getModelsInFolderList(folderName: String): List<ModelEntity>

    @Query("SELECT * FROM models WHERE (folderName = :folderName OR folderName LIKE :folderPrefix) AND isDeleted = 0")
    suspend fun getModelsInHierarchy(folderName: String, folderPrefix: String): List<ModelEntity>

    @Query("SELECT COUNT(*) FROM models WHERE folderName = :folderName AND isDeleted = 0")
    suspend fun getModelCount(folderName: String): Int

    @Query("SELECT COUNT(*) FROM models WHERE (folderName = :folderName OR folderName LIKE :folderPrefix) AND isDeleted = 0")
    suspend fun getModelCountInHierarchy(folderName: String, folderPrefix: String): Int

    @Query("SELECT COUNT(*) FROM models WHERE localFilePath = :localFilePath")
    suspend fun getModelCountByLocalPath(localFilePath: String): Int

    @Insert
    suspend fun insertModel(model: ModelEntity)

    @Update
    suspend fun updateModel(model: ModelEntity)

    // RENAMED: This is now a hard delete (removes from DB permanently)
    @Query("DELETE FROM models WHERE id = :modelId")
    suspend fun hardDeleteModel(modelId: Int)

    // NEW: Soft delete (marks for sync removal)
    @Query("UPDATE models SET isDeleted = 1 WHERE id = :modelId")
    suspend fun softDeleteModel(modelId: Int)

    // Updated: Mark all in folder as deleted (if we implement folder sync later) or just hard delete if needed
    // For now, let's keep hard delete for folder wipes to avoid complexity, or update to soft delete.
    // Given the complexity of folder syncing, for now, "Delete Folder" will still be a hard delete of contents
    // unless we iterate. Let's keep these as hard deletes for now for simplicity in this specific query.
    @Query("DELETE FROM models WHERE folderName = :folderName")
    suspend fun deleteModelsInFolder(folderName: String)

    @Query("DELETE FROM models WHERE folderName = :folderName OR folderName LIKE :folderPrefix")
    suspend fun deleteModelsInHierarchy(folderName: String, folderPrefix: String)

    @Query("DELETE FROM folders WHERE name = :folderName OR name LIKE :folderPrefix")
    suspend fun deleteFolderHierarchy(folderName: String, folderPrefix: String)

    @Query("SELECT COUNT(*) FROM folders WHERE name = :folderName")
    suspend fun getFolderCount(folderName: String): Int

    // --- BACKUP OPERATIONS ---
    @Query("SELECT * FROM models WHERE googleDriveId IS NULL AND isDeleted = 0")
    suspend fun getPendingUploads(): List<ModelEntity>

    // NEW: Get items that need to be removed from Drive
    @Query("SELECT * FROM models WHERE isDeleted = 1 AND googleDriveId IS NOT NULL")
    suspend fun getPendingDeletions(): List<ModelEntity>

    @Query("UPDATE models SET googleDriveId = :driveId WHERE id = :modelId")
    suspend fun markAsUploaded(modelId: Int, driveId: String)
}
