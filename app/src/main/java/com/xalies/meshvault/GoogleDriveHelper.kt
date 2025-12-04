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

    suspend fun uploadFile(localFile: File, mimeType: String, folderName: String): String? = withContext(Dispatchers.IO) {
        if (driveService == null) return@withContext null

        try {
            // 1. Check/Create "MeshVault" folder on Drive
            val parentId = getOrCreateFolder("MeshVault") ?: return@withContext null

            // 2. Check/Create Subfolder (e.g., "SciFi")
            val subFolderId = getOrCreateFolder(folderName, parentId) ?: parentId

            // 3. Upload File
            val metadata = com.google.api.services.drive.model.File()
            metadata.name = localFile.name
            metadata.parents = listOf(subFolderId)

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

    private fun getOrCreateFolder(folderName: String, parentId: String? = null): String? {
        // Query to check if folder exists
        var query = "mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false"
        if (parentId != null) {
            query += " and '$parentId' in parents"
        }

        val result = driveService!!.files().list().setQ(query).setSpaces("drive").execute()
        if (result.files.isNotEmpty()) {
            return result.files[0].id
        }

        // Create if not exists
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