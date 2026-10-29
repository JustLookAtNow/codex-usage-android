package com.codexusage.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeeklyResetRefreshTest {
    private lateinit var context: Context
    private var remaining = 20.0
    private var failUsage = false
    private var failCredits = false
    private var notifications = 0
    private var failNotification = false

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().putString("mode", "import").commit()
        UsageRepository(context).clear()
    }

    private fun repository(deliver: (() -> Unit)? = null) = UsageRepository(context,
        { Credentials("test-token", "test-account") },
        { url ->
            val usage = url.path.endsWith("/usage")
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = if ((usage && failUsage) || (!usage && failCredits)) 403 else 200
                override fun getInputStream() = (if (usage)
                    """{"rate_limit":{"secondary_window":{"limit_window_seconds":604800,"used_percent":${100 - remaining}}}}"""
                    else """{"available_count":2}""").byteInputStream()
            }
        }, {
            // Notification runs after the new result has reached the persistent cache.
            assertEquals(remaining, UsageRepository(context).usage()!!.windows.single().remaining!!, 0.0001)
            notifications++
            if (failNotification) error("notification service unavailable")
            deliver?.invoke()
        })

    @Test fun restartUsesCacheAndUnchangedRefreshDoesNotNotifyAgain() {
        repository().refresh()
        assertEquals(0, notifications)
        remaining = 100.0
        repository().refresh()
        repository().refresh()
        assertEquals(1, notifications)
        remaining = 96.0
        repository().refresh()
        remaining = 98.0
        repository().refresh()
        assertEquals(2, notifications)
    }

    @Test fun failedUsagePreservesBaselineAndFailedCreditsDoesNotSuppressNotification() {
        repository().refresh()
        remaining = 98.0
        failUsage = true
        repository().refresh()
        assertEquals(0, notifications)
        assertEquals(20.0, UsageRepository(context).usage()!!.windows.single().remaining!!, 0.0)
        failUsage = false
        failCredits = true
        repository().refresh()
        assertEquals(1, notifications)
        assertNull(UsageRepository(context).error)
        assertNotNull(UsageRepository(context).creditsError)
    }

    @Test fun failedNotificationDoesNotFailRefreshOrPreventCreditsUpdate() {
        repository().refresh()
        remaining = 100.0
        failNotification = true
        repository().refresh()
        assertEquals(1, notifications)
        assertNull(UsageRepository(context).error)
        assertEquals(2, UsageRepository(context).credits)
        repository().refresh()
        assertEquals(1, notifications)
    }

    @Test fun restoringPermissionDoesNotReplayAMissedReset() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val repository = repository { WeeklyResetNotifications(app).notifyReset() }
        repository.refresh()
        remaining = 100.0
        repository.refresh()
        assertNull(repository.error)
        assertEquals(0, shadowOf(manager).size())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        repository.refresh()
        assertEquals(0, shadowOf(manager).size())
        remaining = 96.0
        repository.refresh()
        remaining = 98.0
        repository.refresh()
        assertEquals(1, shadowOf(manager).size())
    }
}
