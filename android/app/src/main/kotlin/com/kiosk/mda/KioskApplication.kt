package com.kiosk.mda

import android.app.Application
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kiosk.mda.config.ConfigSyncWorker
import java.util.concurrent.TimeUnit

class KioskApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        schedulePeriodicConfigSync()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    private fun schedulePeriodicConfigSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ConfigSyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "config-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
