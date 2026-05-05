package com.kiosk.mda.config

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class ConfigSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = ConfigRepository.get(applicationContext)
        return when (val result = repo.fetchRemote()) {
            is ConfigRepository.SyncResult.Updated -> {
                broadcastConfigUpdated()
                Result.success(workDataOf("status" to "updated"))
            }
            ConfigRepository.SyncResult.NotModified -> Result.success(workDataOf("status" to "not_modified"))
            ConfigRepository.SyncResult.NoUrl -> Result.success(workDataOf("status" to "no_url"))
            is ConfigRepository.SyncResult.HttpError -> Result.retry()
            is ConfigRepository.SyncResult.NetworkError -> Result.retry()
            is ConfigRepository.SyncResult.ParseError -> Result.failure(workDataOf("error" to result.msg))
        }
    }

    private fun broadcastConfigUpdated() {
        val intent = Intent(ACTION_CONFIG_UPDATED).apply {
            setPackage(applicationContext.packageName)
        }
        applicationContext.sendBroadcast(intent)
    }

    companion object {
        const val ACTION_CONFIG_UPDATED = "com.kiosk.mda.CONFIG_UPDATED"
    }
}
