package com.xalies.meshvault

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun resyncExistingVaultContents(
    context: Context,
    dao: ModelDao,
    forceRescan: Boolean = false
) {
    val preferences = context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
    val resyncCompleted = preferences.getBoolean("vault_resync_completed", false)

    if (resyncCompleted && !forceRescan) return

        withContext(Dispatchers.IO) {
            val metadataByPath = mutableMapOf<String, ModelMetadata>()
            val contentResolver = context.contentResolver
        val vaultTreeUri = preferences.getString("vault_tree_uri", null)?.let { Uri.parse(it) }
        val vaultRootDocument = vaultTreeUri?.let { DocumentFile.fromTreeUri(context, it) }
            ?.takeIf { it.exists() && it.isDirectory }

        var scanSucceeded = false

        fun cacheMetadataFromDocument(file: DocumentFile, basePath: String) {
            for (child in file.listFiles()) {
                val name = child.name ?: continue
                val relativePath = if (basePath.isEmpty()) name else "$basePath/$name"

                if (child.isDirectory) {
                    cacheMetadataFromDocument(child, relativePath)
                } else if (name.endsWith(".meta.json", ignoreCase = true)) {
                    runCatching {
                        contentResolver.openInputStream(child.uri)?.bufferedReader()?.use { reader ->
                            reader.readText()
                        }
                    }.getOrNull()?.let { raw ->
                        readModelMetadata(raw)?.let { metadata ->
                            metadataByPath[relativePath.removeSuffix(".meta.json")] =
                                metadata.copy(thumbnailPath = normalizeThumbnailPath(metadata.thumbnailPath))
                        }
                    }
                }
            }
        }

        suspend fun applyDocumentTree(file: DocumentFile, basePath: String) {
            for (child in file.listFiles()) {
                val name = child.name ?: continue
                val relativePath = if (basePath.isEmpty()) name else "$basePath/$name"

                if (child.isDirectory) {
                    if (dao.getFolderCount(relativePath) == 0) {
                        dao.insertFolder(
                            FolderEntity(
                                name = relativePath,
                                color = FOLDER_COLORS.random(),
                                iconName = "Folder"
                            )
                        )
                    }

                    applyDocumentTree(child, relativePath)
                } else {
                    if (name.startsWith("thumb_", ignoreCase = true) || name.endsWith(".meta.json", ignoreCase = true)) continue

                    val folderName = basePath
                    val localPath = "MeshVault/$relativePath"

                    val metadata = metadataByPath[relativePath]

                    if (dao.getModelCountByLocalPath(localPath) == 0) {
                        val restoredTitle = metadata?.title?.takeIf { it.isNotBlank() }
                            ?: name.substringBeforeLast('.', missingDelimiterValue = name)

                        val restoredModel = ModelEntity(
                            title = restoredTitle,
                            pageUrl = metadata?.pageUrl?.takeIf { it.isNotBlank() } ?: child.uri.toString(),
                            localFilePath = localPath,
                            folderName = folderName,
                            thumbnailUrl = normalizeThumbnailPath(metadata?.thumbnailPath),
                            thumbnailData = decodeBase64Image(metadata?.thumbnailDataBase64)
                        )

                        dao.insertModel(restoredModel)
                    }
                }
            }
        }

        if (vaultRootDocument != null) {
            scanSucceeded = true
            cacheMetadataFromDocument(vaultRootDocument, "")
            applyDocumentTree(vaultRootDocument, "")
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val vaultRoot = File(downloadsDir, "MeshVault")

            if (!vaultRoot.exists() || !vaultRoot.isDirectory) return@withContext

            scanSucceeded = true

            vaultRoot.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".meta.json")) {
                    val relativePath = file.relativeTo(vaultRoot).path
                        .removeSuffix(".meta.json")
                        .replace(File.separatorChar, '/')

                    readModelMetadata(file)?.let { metadata ->
                        metadataByPath[relativePath] = metadata.copy(
                            thumbnailPath = normalizeThumbnailPath(metadata.thumbnailPath)
                        )
                    }
                }
            }

            for (file in vaultRoot.walkTopDown()) {
                if (file == vaultRoot) continue

                val relativePath = file.relativeTo(vaultRoot).path.replace(File.separatorChar, '/')

                if (file.isDirectory) {
                    if (dao.getFolderCount(relativePath) == 0) {
                        dao.insertFolder(
                            FolderEntity(
                                name = relativePath,
                                color = FOLDER_COLORS.random(),
                                iconName = "Folder"
                            )
                        )
                    }
                } else {
                    if (file.name.startsWith("thumb_", ignoreCase = true) || file.name.endsWith(".meta.json")) continue

                    val folderName = file.parentFile?.relativeTo(vaultRoot)?.path?.replace(File.separatorChar, '/') ?: ""
                    val localPath = "MeshVault/${file.relativeTo(vaultRoot).path.replace(File.separatorChar, '/')}"

                    val metadata = metadataByPath[relativePath]

                    if (dao.getModelCountByLocalPath(localPath) == 0) {
                        val restoredTitle = metadata?.title?.takeIf { it.isNotBlank() }
                            ?: file.nameWithoutExtension.ifBlank { file.name }

                        val restoredModel = ModelEntity(
                            title = restoredTitle,
                            pageUrl = metadata?.pageUrl?.takeIf { it.isNotBlank() } ?: file.toURI().toString(),
                            localFilePath = localPath,
                            folderName = folderName,
                            thumbnailUrl = normalizeThumbnailPath(metadata?.thumbnailPath),
                            thumbnailData = decodeBase64Image(metadata?.thumbnailDataBase64)
                        )

                        dao.insertModel(restoredModel)
                    }
                }
            }
        }

        if (scanSucceeded) {
            preferences.edit().putBoolean("vault_resync_completed", true).apply()
        }
    }
}

private fun normalizeThumbnailPath(raw: String?): String? {
    if (raw.isNullOrBlank()) return raw

    val vaultPrefix = "MeshVault/"

    var cleaned = raw.removePrefix("/")

    if (cleaned.startsWith(vaultPrefix)) {
        cleaned = cleaned.removePrefix(vaultPrefix)
    } else if (cleaned.contains("/MeshVault/")) {
        cleaned = cleaned.substringAfter("/MeshVault/")
    }

    return cleaned.ifBlank { null }
}

