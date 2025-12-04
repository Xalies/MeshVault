package com.xalies.meshvault
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val name: String,
    val color: Long = 0xFF49454F, // Default Dark Grey
    val iconName: String = "Folder" // Default Icon
)