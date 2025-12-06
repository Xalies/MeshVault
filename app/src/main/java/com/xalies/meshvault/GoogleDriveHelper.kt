package com.xalies.meshvault

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class GoogleDriveHelper(private val context: Context) {

    private val driveService: Drive? by lazy {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null) {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account.account

            Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("MeshVault").build()
        } else {
            null
        }
    }

    suspend fun uploadFile(localFile: File, mimeType: String, folderPath: String): String? = withContext(Dispatchers.IO) {
        if (driveService == null) return@withContext null

        try {
            var currentParentId = getOrCreateFolder("MeshVault") ?: return@withContext null

            val folders = folderPath.split("/").filter { it.isNotBlank() }

            for (folderName in folders) {
                currentParentId = getOrCreateFolder(folderName, currentParentId) ?: currentParentId
            }

            val metadata = com.google.api.services.drive.model.File()
            metadata.name = localFile.name
            metadata.parents = listOf(currentParentId)

            val content = FileContent(mimeType, localFile)
            val uploadedFile = driveService!!.files().create(metadata, content)
                .setFields("id")
                .execute()

            return@withContext uploadedFile.id
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun trashFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        if (driveService == null) return@withContext false
        try {
            val updates = com.google.api.services.drive.model.File()
            updates.trashed = true
            driveService!!.files().update(fileId, updates).execute()
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    private fun getOrCreateFolder(folderName: String, parentId: String? = null): String? {
        var query = "mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false"
        if (parentId != null) {
            query += " and '$parentId' in parents"
        }

        val result = driveService!!.files().list().setQ(query).setSpaces("drive").execute()
        if (result.files.isNotEmpty()) {
            return result.files[0].id
        }

        val metadata = com.google.api.services.drive.model.File()
        metadata.name = folderName
        metadata.mimeType = "application/vnd.google-apps.folder"
        if (parentId != null) {
            metadata.parents = listOf(parentId)
        }

        val folder = driveService!!.files().create(metadata).setFields("id").execute()
        return folder.id
    }
}