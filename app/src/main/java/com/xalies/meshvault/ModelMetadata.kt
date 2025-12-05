package com.xalies.meshvault

data class ModelMetadata(
    val title: String,
    val pageUrl: String,
    val thumbnailPath: String? = null,
    val thumbnailDataBase64: String? = null
)
