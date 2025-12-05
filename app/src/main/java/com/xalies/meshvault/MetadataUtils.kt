package com.xalies.meshvault

import org.json.JSONObject
import java.io.File

fun readModelMetadata(metadataFile: File): ModelMetadata? {
    return try {
        val raw = metadataFile.readText()
        val json = JSONObject(raw)

        ModelMetadata(
            title = json.optString("title"),
            pageUrl = json.optString("pageUrl"),
            thumbnailPath = json.optString("thumbnailPath").takeIf { it.isNotBlank() }
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
    }

    metadataFile.writeText(json.toString())
}
