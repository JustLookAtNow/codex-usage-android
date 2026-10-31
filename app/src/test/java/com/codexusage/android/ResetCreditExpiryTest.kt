package com.codexusage.android

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResetCreditExpiryTest {
    private val now = 1800000000L
    private fun credit(remaining: Long?, status: String? = "available") =
        ResetCredit("one", null, status, null, remaining?.let { now + it }, null, null)

    @Test fun thresholdsUseUnroundedTimeAndExcludeExpiredOrUnavailableCredits() {
        for ((remaining, expected) in listOf(
            -1L to ResetExpiryLevel.NORMAL, 0L to ResetExpiryLevel.NORMAL,
            1L to ResetExpiryLevel.CRITICAL, 86400L to ResetExpiryLevel.CRITICAL,
            86401L to ResetExpiryLevel.WARNING, 259200L to ResetExpiryLevel.WARNING,
            259201L to ResetExpiryLevel.NORMAL
        )) assertEquals("remaining=$remaining", expected, credit(remaining).expiryLevel(now))
        assertEquals(ResetExpiryLevel.NORMAL, credit(null).expiryLevel(now))
        for (status in listOf(null, "expired", "consumed", "redeemed", "revoked", "future")) {
            assertEquals(ResetExpiryLevel.NORMAL, credit(1, status).expiryLevel(now))
        }
    }

    @Test fun summaryUsesMostUrgentItemButHonorsZeroAndUnknownCounts() {
        val rows = listOf(credit(200000), credit(100), credit(null))
        assertEquals(ResetExpiryLevel.CRITICAL, ResetCredits(3, null, rows).expiryLevel(now))
        assertEquals(ResetExpiryLevel.NORMAL, ResetCredits(0, null, rows).expiryLevel(now))
        assertEquals(ResetExpiryLevel.NORMAL, ResetCredits(2, null, null).expiryLevel(now))
        assertEquals(ResetExpiryLevel.NORMAL, (null as ResetCredits?).expiryLevel(now))
    }

    @Test fun labelsAndFallbackKeysRemainStableAcrossParsing() {
        assertEquals("— resets", resetCountLabel(null))
        assertEquals("0 resets", resetCountLabel(0))
        assertEquals("1 reset", resetCountLabel(1))
        assertEquals("2 resets", resetCountLabel(2))
        val item = credit(100).copy(id = null, grantedAt = now - 10, resetType = "codex_rate_limits")
        val parsed = ResetCreditsParser.parse(ResetCreditsParser.displayJson(ResetCredits(1, null, listOf(item))))
        assertEquals(item.expiryNotificationKey(), parsed.credits!!.single().expiryNotificationKey())
        assertNotEquals(item.expiryNotificationKey(), item.copy(expiresAt = now + 200).expiryNotificationKey())
    }

    @Test fun expiryColorsSupportRegularAndGlassPalettes() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(123, resetExpiryColor(context, ResetExpiryLevel.NORMAL, 123))
        assertEquals(context.getColor(R.color.quota_warning), resetExpiryColor(context, ResetExpiryLevel.WARNING, 123))
        assertEquals(context.getColor(R.color.quota_critical), resetExpiryColor(context, ResetExpiryLevel.CRITICAL, 123))
        assertEquals(context.getColor(R.color.widget_glass_warning), resetExpiryColor(context, ResetExpiryLevel.WARNING, 123, true))
        assertEquals(context.getColor(R.color.widget_glass_critical), resetExpiryColor(context, ResetExpiryLevel.CRITICAL, 123, true))
    }
}
