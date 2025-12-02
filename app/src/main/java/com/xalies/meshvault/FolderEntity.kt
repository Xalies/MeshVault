package com.xalies.meshvault
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val name: String // Folder name is unique ID
)