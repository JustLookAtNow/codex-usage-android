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
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            if (CredentialStore(applicationContext).mode != null) UsageRepository(applicationContext).refresh()
        } finally {
            if (inputData.getBoolean(MANUAL_REFRESH, false)) WidgetRefreshState.finish(applicationContext)
            UsageWidget.updateAll(applicationContext)
        }
        // Errors are displayed; the next periodic/manual run retries subject to Retry-After.
        return Result.success()
    }

    companion object {
        internal const val MANUAL_REFRESH = "manual_refresh"
        fun schedule(context: Context) = synchronized(ConfigurationLock) {
            if (CredentialStore(context).mode == null) return@synchronized
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("quota-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }

        fun now(context: Context) = synchronized(ConfigurationLock) {
            if (CredentialStore(context).mode == null) return@synchronized
            if (WidgetRefreshState.active(context)) return@synchronized
            // No network constraint: an offline tap should produce visible feedback immediately.
            WidgetRefreshState.start(context)
            UsageWidget.updateAll(context)
            try {
                WorkManager.getInstance(context).enqueueUniqueWork("quota-manual", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RefreshWorker>()
                        .setInputData(workDataOf(MANUAL_REFRESH to true)).build())
            } catch (error: Exception) {
                WidgetRefreshState.clear(context)
                UsageWidget.updateAll(context)
                throw error
            }
        }

        fun cancel(context: Context) {
            WidgetRefreshState.clear(context)
            WidgetCountdown.cancel(context)
            WorkManager.getInstance(context).cancelUniqueWork("quota-periodic")
            WorkManager.getInstance(context).cancelUniqueWork("quota-manual")
        }
    }
}
