package com.codexusage.android

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import org.robolectric.Shadows.shadowOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResetRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var account = "test-account"
    private var status = "available"
    private var listedCreditId = "selected"
    private var available = 1
    private var hideCredit = false
    private var omitCredits = false
    private var failUsage = false
    private var failCredits = false
    private var timeoutPost = false
    private var postCode = "reset"
    private val posts = mutableListOf<String>()
    private var usageBody = """{"rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":80,"reset_at":1800000000},"secondary_window":{"limit_window_seconds":604800,"used_percent":60}},"additional_rate_limits":[{"limit_name":"Extra","rate_limit":{"primary_window":{"limit_window_seconds":18000,"used_percent":30}}}]}"""

    @Before fun setup() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear()
            .putString("mode", "import").commit()
        UsageRepository(context).clear()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun repository() = UsageRepository(context,
        loadCredentials = { Credentials("test-token", account) },
        openConnection = { url -> object : HttpURLConnection(url) {
            private val output = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = output
            override fun getResponseCode(): Int {
                assertEquals(5000, connectTimeout)
                assertEquals(10000, readTimeout)
                if (url.path.endsWith("/consume")) {
                    posts += output.toString("UTF-8")
                    if (timeoutPost || postCode in listOf("reset", "already_redeemed")) {
                        available = 0
                        status = "consumed"
                    }
                    if (timeoutPost) throw SocketTimeoutException()
                }
                if (failUsage && url.path.endsWith("/usage")) throw SocketTimeoutException()
                return if (failCredits && url.path.endsWith("/rate-limit-reset-credits")) 403 else 200
            }
            override fun getInputStream() = when {
                url.path.endsWith("/consume") -> """{"code":"$postCode"}"""
                url.path.endsWith("/usage") -> usageBody
                omitCredits -> """{"available_count":$available}"""
                else -> """{"available_count":$available,"credits":${if (hideCredit) "[]" else """[{"id":"$listedCreditId","status":"$status"}]"""}}"""
            }.byteInputStream()
        } },
        notifyWeeklyReset = {}, notifyNewResetCredits = { _, _ -> },
        notifyExpiringResetCredits = { _, _ -> })

    private fun timedOutReset() {
        repository().refresh()
        timeoutPost = true
        assertThrows(UserError::class.java) { repository().consumeReset("selected") }
        assertTrue(repository().resetPending)
        assertTrue(repository().resetMessage!!.contains("连接超时"))
    }

    @Test fun timeoutReconcilesAfterRestartEvenWhenUsageFailsWithoutAnotherPost() {
        timedOutReset()
        val timestamp = repository().updatedAt
        failUsage = true
        val restarted = repository()
        restarted.refresh()
        assertEquals(1, posts.size)
        assertFalse(restarted.resetPending)
        assertEquals(0, restarted.credits)
        assertEquals("consumed", restarted.resetCredits()!!.credits!!.single().status)
        assertFalse(restarted.resetMessage!!.contains("超时"))
        assertTrue(restarted.resetMessage!!.contains("已确认"))
        assertEquals(timestamp, restarted.updatedAt)
        assertNotNull(restarted.error) // A separate query failure does not undo reset success.
        assertTrue(restarted.displayUsage()!!.windows.all { it.remaining == 100.0 })
        val event = restarted.claimResetCelebration()!!
        assertEquals(org.json.JSONObject(posts.single()).getString("redeem_request_id"), event.requestId)
        assertEquals(20.0, event.before!!.windows.first().remaining!!, 0.0)
        restarted.refresh()
        assertNull(repository().claimResetCelebration())
    }

    @Test fun confirmedResetKeepsAllWindowsFullUntilNextSuccessfulIndependentUsageRefresh() {
        repository().refresh()
        val result = repository().consumeReset("selected")
        assertEquals(ResetResult.RESET, result)
        assertTrue(result.succeeded)
        assertEquals(listOf(20.0, 40.0, 70.0), repository().usage()!!.windows.map { it.remaining })
        assertEquals(listOf(100.0, 100.0, 100.0), repository().displayUsage()!!.windows.map { it.remaining })
        assertEquals(repository().usage()!!.windows.map { it.resetAt }, repository().displayUsage()!!.windows.map { it.resetAt })
        assertNotNull(repository().claimResetCelebration())
        failUsage = true
        repository().refresh()
        assertTrue(repository().displayUsage()!!.windows.all { it.remaining == 100.0 })
        failUsage = false
        failCredits = true
        repository().refresh()
        assertEquals(repository().usage(), repository().displayUsage())
        assertNotNull(repository().creditsError)
        assertFalse(repository().resetPending)
        assertNull(repository().claimResetCelebration())
    }

    @Test fun readonlyRecoveryAlsoShowsFullValuesWhenUsageResponseStillContainsOldValues() {
        timedOutReset()
        status = "redeemed"
        repository().refresh()
        assertFalse(repository().resetPending)
        assertTrue(repository().displayUsage()!!.windows.all { it.remaining == 100.0 })
        repository().refresh()
        assertEquals(repository().usage(), repository().displayUsage())
        assertEquals(1, posts.size)
    }

    @Test fun countDropExpiredOrStillAvailableCreditCannotConfirmSuccess() {
        timedOutReset()
        usageBody = usageBody.replace(Regex("\"used_percent\":\\d+"), "\"used_percent\":0")
        for (state in listOf("available", "expired", "revoked")) {
            status = state
            repository().refresh()
            assertTrue(repository().resetPending)
            assertNull(repository().claimResetCelebration())
        }
        assertEquals(repository().usage(), repository().displayUsage())
        assertEquals(1, posts.size)
    }

    @Test fun anotherAccountOrFailedCreditQueryCannotConfirmOriginalRequest() {
        timedOutReset()
        account = "other-account"
        repository().refresh()
        assertTrue(repository().resetPending)
        assertNull(repository().claimResetCelebration())
        assertThrows(UserError::class.java) { repository().consumeReset() }
        account = "test-account"
        failCredits = true
        repository().refresh()
        assertTrue(repository().resetPending)
        assertNull(repository().claimResetCelebration())
        assertEquals(1, posts.size)
    }

    @Test fun manualRetryUsesSamePayloadAndCelebratesAlreadyRedeemedOnlyOnce() {
        timedOutReset()
        timeoutPost = false
        postCode = "already_redeemed"
        assertEquals(ResetResult.ALREADY_REDEEMED, repository().consumeReset())
        assertEquals(posts[0], posts[1])
        assertNotNull(repository().claimResetCelebration())
        assertNull(repository().claimResetCelebration())
        assertTrue(repository().displayUsage()!!.windows.all { it.remaining == 100.0 })
    }

    @Test fun nonSuccessResultsNeverCreateFullDisplayOrCelebration() {
        for (code in listOf("nothing_to_reset", "no_credit")) {
            available = 1
            status = "available"
            postCode = code
            assertFalse(repository().consumeReset("selected").succeeded)
            assertFalse(repository().resetPending)
            assertNull(repository().claimResetCelebration())
            assertEquals(repository().usage(), repository().displayUsage())
        }
    }

    @Test fun bothWidgetStylesAndAllSizesShowFullThenRestoreRealValues() {
        val manager = AppWidgetManager.getInstance(context)
        val widgets = shadowOf(manager)
        val ids = listOf(UsageWidget::class.java, GlassUsageWidget::class.java).flatMap { provider ->
            listOf(120 to 64, 320 to 64, 320 to 164).map { (width, height) ->
                widgets.createWidget(provider, R.layout.usage_widget).also { id ->
                    manager.updateAppWidgetOptions(id, Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                    })
                }
            }
        }
        repository().refresh()
        repository().consumeReset("selected")
        UsageWidget.updateAll(context)
        ids.forEach { id ->
            val view = widgets.getViewFor(id)
            assertEquals("100%", view.findViewById<TextView>(R.id.widget_primary_value).text.toString())
            assertEquals("100%", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
        }
        repository().refresh()
        UsageWidget.updateAll(context)
        ids.forEach { id ->
            val view = widgets.getViewFor(id)
            assertEquals("20%", view.findViewById<TextView>(R.id.widget_primary_value).text.toString())
            assertEquals("40%", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
        }
    }

    @Test fun confirmingSuccessNeverExposesTheOldCreditAsAvailableToObservers() {
        val repository = repository()
        repository.refresh()
        var staleAvailable = false
        val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (repository.resetMessage == ResetResult.RESET.message && !repository.resetPending &&
                repository.credits == 1 && repository.resetCredits()?.credits?.any {
                    it.id == "selected" && it.status == "available"
                } == true) staleAvailable = true
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            repository.consumeReset("selected")
            assertFalse(staleAvailable)
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Test fun missingOldCreditAutomaticallyConfirmsWhilePreservingTheDifferentAvailableCredit() {
        timedOutReset()
        listedCreditId = "another-credit"
        status = "available"
        available = 1
        val requestId = repository().pendingResetRequestId!!
        repository().refresh()
        val repository = repository()
        assertFalse(repository.resetPending)
        assertFalse(repository.pendingResetCreditMissing)
        assertEquals(ResetResult.ALREADY_REDEEMED.message, repository.resetMessage)
        assertEquals(1, repository.credits)
        assertEquals("another-credit", repository.resetCredits()!!.credits!!.single().id)
        assertEquals("available", repository.resetCredits()!!.credits!!.single().status)
        assertTrue(repository.displayUsage()!!.windows.all { it.remaining == 100.0 })
        assertEquals(requestId, repository.claimResetCelebration()!!.requestId)
        assertEquals(1, posts.size)
        repository.refresh()
        assertFalse(repository.resetPending)
        assertNull(repository.claimResetCelebration())
        assertEquals(repository.usage(), repository.displayUsage())
    }

    @Test fun explicitEmptyListConfirmsMissingCreditEvenWhenUsageQueryFails() {
        timedOutReset()
        hideCredit = true
        failUsage = true
        repository().refresh()
        assertFalse(repository().resetPending)
        assertEquals(0, repository().credits)
        assertEquals(ResetResult.ALREADY_REDEEMED.message, repository().resetMessage)
        assertNotNull(repository().claimResetCelebration())
        assertEquals(1, posts.size)
    }

    @Test fun missingCreditDoesNotConfirmOnAnotherAccountOrWhenFreshListIsUnavailable() {
        timedOutReset()
        hideCredit = true
        account = "other-account"
        repository().refresh()
        assertTrue(repository().resetPending)
        account = "test-account"
        failCredits = true
        repository().refresh()
        assertTrue(repository().resetPending)
        failCredits = false
        omitCredits = true
        repository().refresh()
        assertTrue(repository().resetPending)
        omitCredits = false
        hideCredit = false
        listedCreditId = ""
        repository().refresh()
        assertTrue(repository().resetPending)
        assertNull(repository().claimResetCelebration())
        assertEquals(1, posts.size)
    }

    @Test fun acknowledgementIsLocalAndCannotClearAChangedRequest() {
        timedOutReset()
        val requestId = repository().pendingResetRequestId!!
        val local = UsageRepository(context, { error("must not read credentials") }, { error("must not send a request") })
        assertThrows(UserError::class.java) { local.acknowledgeCompletedReset("different-request") }
        assertTrue(local.resetPending)
        assertEquals("已确认上次重置完成", local.acknowledgeCompletedReset(requestId))
        assertFalse(local.resetPending)
        assertNull(local.claimResetCelebration())
        assertThrows(UserError::class.java) { local.acknowledgeCompletedReset(requestId) }
    }

    @Test fun clearRemovesPendingFullDisplayAndUnplayedEvents() {
        repository().refresh()
        repository().consumeReset("selected")
        repository().clear()
        assertNull(repository().displayUsage())
        assertNull(repository().claimResetCelebration())
        assertFalse(repository().resetPending)
    }
}
