package com.xalies.meshvault

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class BackupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val dao = database.modelDao()
        val driveHelper = GoogleDriveHelper(applicationContext)

        var hasActivity = false

        // 1. Process Uploads
        val pendingModels = dao.getPendingUploads()
        for (model in pendingModels) {
            val file = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), model.localFilePath)

            if (file.exists()) {
                val driveId = driveHelper.uploadFile(file, "application/zip", model.folderName)
                if (driveId != null) {
                    dao.markAsUploaded(model.id, driveId)
                    hasActivity = true
                }
            }
        }

        // 2. Process Deletions (Sync Deletes)
        val pendingDeletions = dao.getPendingDeletions()
        for (model in pendingDeletions) {
            if (model.googleDriveId != null) {
                val success = driveHelper.trashFile(model.googleDriveId)
                if (success) {
                    // Permanently remove from local DB after cloud delete
                    dao.hardDeleteModel(model.id)
                    hasActivity = true
                }
            } else {
                // Should technically not happen due to query, but safe fallback
                dao.hardDeleteModel(model.id)
            }
        }

        // If we did nothing, we can retry later or just succeed.
        // Returning success usually fine unless there was a specific error.
        return Result.success()
    }
}