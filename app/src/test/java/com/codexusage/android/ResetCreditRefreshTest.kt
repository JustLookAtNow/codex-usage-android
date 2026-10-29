package com.codexusage.android

import android.Manifest
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResetCreditRefreshTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var available = 2
    private var failCredits = false
    private var malformedCredits = false
    private var failDelivery = false
    private var failPostRefresh = false
    private val delivered = mutableListOf<Pair<Int, Int>>()

    @Before fun setup() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear()
            .putString("mode", "import").commit()
        UsageRepository(context).clear()
    }

    private fun repository(deliver: ((Int, Int) -> Unit)? = null) = UsageRepository(context,
        loadCredentials = { Credentials("test-token", "test-account") },
        openConnection = { url -> object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode() = if (failCredits && url.path.endsWith("/rate-limit-reset-credits")) 403 else 200
            override fun getInputStream() = when {
                url.path.endsWith("/consume") -> {
                    available--
                    failCredits = failPostRefresh
                    """{"code":"reset"}"""
                }
                url.path.endsWith("/usage") -> """{"rate_limit":{"secondary_window":{"limit_window_seconds":604800,"used_percent":50}}}"""
                malformedCredits -> "{}"
                else -> """{"available_count":$available}"""
            }.byteInputStream()
        } },
        notifyWeeklyReset = {},
        notifyNewResetCredits = { added, current ->
            assertEquals(current, UsageRepository(context).credits)
            delivered += added to current
            if (failDelivery) error("notification service unavailable")
            deliver?.invoke(added, current)
        })

    @Test fun firstQueryAndUnchangedCountsDoNotNotifyButIncreasesSurviveRestart() {
        repository().refresh()
        repository().refresh()
        assertTrue(delivered.isEmpty())
        available = 5
        repository().refresh()
        repository().refresh()
        assertEquals(listOf(3 to 5), delivered)
        available = 1
        repository().refresh()
        available = 2
        repository().refresh()
        assertEquals(listOf(3 to 5, 1 to 2), delivered)
    }

    @Test fun failedAndMalformedQueriesPreserveBaselineUntilRecovery() {
        repository().refresh()
        available = 4
        failCredits = true
        repository().refresh()
        failCredits = false
        malformedCredits = true
        repository().refresh()
        assertTrue(delivered.isEmpty())
        assertEquals(2, UsageRepository(context).credits)
        malformedCredits = false
        repository().refresh()
        assertEquals(listOf(2 to 4), delivered)
        assertNull(UsageRepository(context).creditsError)
    }

    @Test fun upgradeUsesExistingCacheAndClearRebuildsBaseline() {
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putInt("credits", 1).commit()
        repository().refresh()
        assertEquals(listOf(1 to 2), delivered)
        repository().clear()
        available = 5
        repository().refresh()
        assertEquals(listOf(1 to 2), delivered)
    }

    @Test fun deliveryFailureDoesNotFailQueryOrReplayNotification() {
        repository().refresh()
        available = 3
        failDelivery = true
        repository().refresh()
        assertNull(UsageRepository(context).error)
        assertNull(UsageRepository(context).creditsError)
        assertEquals(3, UsageRepository(context).credits)
        repository().refresh()
        assertEquals(listOf(1 to 3), delivered)
    }

    @Test fun restoringPermissionDoesNotReplayMissedNotification() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.cancelAll()
        val repository = repository { added, current -> ResetCreditNotifications(context).notifyAdded(added, current) }
        repository.refresh()
        available = 3
        repository.refresh()
        assertEquals(0, shadowOf(manager).size())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        repository.refresh()
        assertEquals(0, shadowOf(manager).size())
        available = 4
        repository.refresh()
        assertEquals(1, shadowOf(manager).size())
    }

    @Test fun consumeChecksForIncreasesBeforeUseAndRecordsReducedCountAfterUse() {
        repository().refresh()
        available = 4
        repository().consumeReset()
        assertEquals(listOf(2 to 4), delivered)
        assertEquals(3, UsageRepository(context).credits)
        available = 4
        repository().refresh()
        assertEquals(listOf(2 to 4, 1 to 4), delivered)
    }

    @Test fun consumeCacheClearRetainsBaselineWhenPostRefreshFails() {
        repository().refresh()
        failPostRefresh = true
        repository().consumeReset()
        assertNull(UsageRepository(context).credits)
        failCredits = false
        available = 3
        repository().refresh()
        assertEquals(listOf(1 to 3), delivered)
    }
}
