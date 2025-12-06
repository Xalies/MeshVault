package com.xalies.meshvault

import android.os.Environment
import org.json.JSONObject
import java.io.File

fun readModelMetadata(metadataFile: File): ModelMetadata? {
    return runCatching { readModelMetadata(metadataFile.readText()) }.getOrNull()
}

fun readModelMetadata(raw: String): ModelMetadata? {
    return try {
        val json = JSONObject(raw)

        ModelMetadata(
            title = json.optString("title"),
            pageUrl = json.optString("pageUrl"),
            thumbnailPath = json.optString("thumbnailPath").takeIf { it.isNotBlank() },
            thumbnailDataBase64 = json.optString("thumbnailDataBase64").takeIf { it.isNotBlank() }
        )
    } catch (e: Exception) {
        null
    }
}

fun writeModelMetadata(metadataFile: File, metadata: ModelMetadata) {
    val json = JSONObject().apply {
        put("title", metadata.title)
        put("pageUrl", metadata.pageUrl)
        metadata.thumbnailPath?.let { put("thumbnailPath", it) }
        metadata.thumbnailDataBase64?.let { put("thumbnailDataBase64", it) }
    }

    metadataFile.writeText(json.toString())
}

fun metadataFromModel(model: ModelEntity): ModelMetadata {
    val thumbnailBytes = model.thumbnailData ?: loadThumbnailBytes(model.thumbnailUrl)

    return ModelMetadata(
        title = model.title,
        pageUrl = model.pageUrl,
        thumbnailPath = model.thumbnailUrl,
        thumbnailDataBase64 = thumbnailBytes?.let { encodeThumbnailToBase64(it) }
    )
}

fun writeMetadataForModel(model: ModelEntity) {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val dataFile = File(downloadsDir, model.localFilePath)
    val parent = dataFile.parentFile ?: return
    val metadataFile = File(parent, "${dataFile.name}.meta.json")

    writeModelMetadata(metadataFile, metadataFromModel(model))
}

private fun loadThumbnailBytes(thumbnailUrl: String?): ByteArray? {
    if (thumbnailUrl.isNullOrBlank()) return null
    if (thumbnailUrl.startsWith("http", ignoreCase = true)) return null

    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val cleanedPath = thumbnailUrl.removePrefix("/").let { raw ->
        if (raw.startsWith("MeshVault/")) raw.removePrefix("MeshVault/") else raw
    }

    val thumbnailFile = File(downloadsDir, "MeshVault/$cleanedPath")
    if (thumbnailFile.exists()) {
        return runCatching { thumbnailFile.readBytes() }.getOrNull()
    }

    return null
}
