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

        // 1. Get pending files
        val pendingModels = dao.getPendingUploads()
        if (pendingModels.isEmpty()) return Result.success()

        var successCount = 0

        // 2. Upload loop
        for (model in pendingModels) {
            // Find the actual file
            val file = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), model.localFilePath)

            if (file.exists()) {
                // Upload
                val driveId = driveHelper.uploadFile(file, "application/zip", model.folderName)

                if (driveId != null) {
                    // 3. Mark as uploaded in DB
                    dao.markAsUploaded(model.id, driveId)
                    successCount++
                }
            }
        }

        return if (successCount > 0) Result.success() else Result.retry()
    }
}