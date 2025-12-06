package com.xalies.meshvault

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val pageUrl: String,
    val localFilePath: String, // Where the zip/stl is
    val folderName: String,    // Replaced "category" with "folderName" to be clear
    val thumbnailUrl: String? = null,
    val thumbnailData: ByteArray? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val googleDriveId: String? = null,
    val isDeleted: Boolean = false
)