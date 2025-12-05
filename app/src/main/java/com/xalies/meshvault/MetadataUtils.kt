package com.xalies.meshvault

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
