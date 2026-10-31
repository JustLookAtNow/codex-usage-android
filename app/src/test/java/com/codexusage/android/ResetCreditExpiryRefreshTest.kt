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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResetCreditExpiryRefreshTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var now = 1800000000L
    private var rows = listOf(credit("one", 80))
    private var count = 1
    private var failQuery = false
    private var malformed = false
    private var failDelivery = false
    private val delivered = mutableListOf<List<String?>>()

    private fun credit(id: String?, hours: Int) = ResetCredit(id, "codex_rate_limits", "available",
        1799000000L, 1800000000L + hours * 3600L, null, null)

    @Before fun setup() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().putString("mode", "test").commit()
        UsageRepository(context).clear()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun repository(deliver: ((List<ResetCredit>, Long) -> Unit)? = null) = UsageRepository(context,
        loadCredentials = { Credentials("test-token", "test-account") },
        openConnection = { url -> object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode() = if (failQuery && url.path.endsWith("/rate-limit-reset-credits")) 403 else 200
            override fun getInputStream() = when {
                url.path.endsWith("/consume") -> {
                    count = 0
                    rows = emptyList()
                    """{"code":"reset"}"""
                }
                url.path.endsWith("/usage") -> """{"rate_limit":{"secondary_window":{"limit_window_seconds":604800,"used_percent":50}}}"""
                malformed -> "{}"
                else -> ResetCreditsParser.displayJson(ResetCredits(count, null, rows))
            }.byteInputStream()
        } },
        notifyWeeklyReset = {}, notifyNewResetCredits = { _, _ -> },
        notifyExpiringResetCredits = { items, time ->
            // Cache and deduplication must already be durable before delivery is attempted.
            assertNotNull(context.getSharedPreferences("usage", Context.MODE_PRIVATE).getString("credits_expiry_stages", null))
            assertEquals(count, UsageRepository(context).credits)
            delivered += items.map { it.id }
            if (failDelivery) error("notification service unavailable")
            deliver?.invoke(items, time)
        }, nowMillis = { now * 1000 })

    @Test fun warnsAtEachStageOnceAcrossRepositoryRecreation() {
        repository().refresh()
        assertTrue(delivered.isEmpty())
        now += 8 * 3600
        repository().refresh()
        repository().refresh()
        assertEquals(listOf(listOf("one")), delivered)
        now += 48 * 3600
        repository().refresh()
        repository().refresh()
        assertEquals(listOf(listOf("one"), listOf("one")), delivered)
        now += 24 * 3600
        repository().refresh()
        assertEquals(2, delivered.size)
    }

    @Test fun firstCriticalDiscoveryCoversBothStagesAndBatchesEligibleItems() {
        rows = listOf(credit("critical", 1), credit("warning", 50), credit(null, 60),
            credit("expired", 0).copy(status = "expired"), credit("used", 1).copy(status = "consumed"),
            credit("unknown", 1).copy(expiresAt = null))
        count = 4
        repository().refresh()
        repository().refresh()
        assertEquals(listOf(listOf("critical", "warning", null)), delivered)
        // Moving the clock back into the warning stage must not replay the first critical alert.
        now -= 24 * 3600
        repository().refresh()
        assertEquals(1, delivered.size)
    }

    @Test fun failedAndMalformedQueriesDoNotAdvanceStagesAndZeroCountDoesNotAlert() {
        rows = listOf(credit("one", 60))
        failQuery = true
        repository().refresh()
        failQuery = false
        malformed = true
        repository().refresh()
        assertTrue(delivered.isEmpty())
        malformed = false
        count = 0
        repository().refresh()
        assertTrue(delivered.isEmpty())
        count = 1
        repository().refresh()
        assertEquals(listOf(listOf("one")), delivered)
        repository().clear()
        repository().refresh()
        assertEquals(2, delivered.size)
    }

    @Test fun deliveryFailureDoesNotFailRefreshOrReplayButLaterCriticalStillAlerts() {
        rows = listOf(credit("one", 60))
        failDelivery = true
        repository().refresh()
        assertNull(UsageRepository(context).creditsError)
        assertNull(UsageRepository(context).error)
        failDelivery = false
        repository().refresh()
        assertEquals(1, delivered.size)
        now += 36 * 3600
        repository().refresh()
        assertEquals(2, delivered.size)
    }

    @Test fun deniedPermissionDoesNotReplayAfterPermissionReturns() {
        rows = listOf(credit("one", 60))
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        fun refresh() = repository { items, time -> ResetCreditExpiryNotifications(context).notifyExpiring(items, time) }.refresh()
        refresh()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(0, shadowOf(manager).size())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        refresh()
        assertEquals(0, shadowOf(manager).size())
        now += 36 * 3600
        refresh()
        assertEquals(1, shadowOf(manager).size())
    }

    @Test fun foregroundConsumeChecksExpiryAndConsumedItemDoesNotAlertAgain() {
        rows = listOf(credit("one", 1))
        repository().consumeReset("one")
        repository().refresh()
        assertEquals(listOf(listOf("one")), delivered)
        assertEquals(0, UsageRepository(context).credits)
    }
}
