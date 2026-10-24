package com.codexusage.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (CredentialStore(applicationContext).mode == null) return Result.success()
        UsageRepository(applicationContext).refresh()
        UsageWidget.updateAll(applicationContext)
        // Errors are displayed; the next periodic/manual run retries subject to Retry-After.
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) = synchronized(ConfigurationLock) {
            if (CredentialStore(context).mode == null) return@synchronized
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("quota-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }

        fun now(context: Context) = synchronized(ConfigurationLock) {
            if (CredentialStore(context).mode == null) return@synchronized
            // No network constraint: an offline tap should produce visible feedback immediately.
            WorkManager.getInstance(context).enqueueUniqueWork("quota-manual", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>().build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("quota-periodic")
            WorkManager.getInstance(context).cancelUniqueWork("quota-manual")
        }
    }
}
