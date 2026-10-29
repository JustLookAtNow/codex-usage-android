package com.codexusage.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyResetPolicyTest {
    @Test fun notifiesWhenMainWeeklyRemainingRisesAbove95Percent() {
        for ((previous, current) in listOf(
            20.0 to 100.0,
            94.0 to 96.0,
            95.0 to 95.1,
            96.0 to 98.0,
        )) {
            assertTrue(
                "Expected $previous -> $current to notify",
                shouldNotifyWeeklyReset(usage(weekly(previous)), usage(weekly(current))),
            )
        }
    }

    @Test fun requiresAnIncreaseAndAValueStrictlyAbove95Percent() {
        for ((previous, current) in listOf(
            90.0 to 95.0,
            100.0 to 100.0,
            96.0 to 90.0,
        )) {
            assertFalse(
                "Expected $previous -> $current not to notify",
                shouldNotifyWeeklyReset(usage(weekly(previous)), usage(weekly(current))),
            )
        }
    }

    @Test fun doesNotNotifyWhenPreviousOrCurrentWeeklyDataIsMissing() {
        assertFalse(shouldNotifyWeeklyReset(null, usage(weekly(99.0))))
        assertFalse(shouldNotifyWeeklyReset(usage(), usage(weekly(99.0))))
        assertFalse(shouldNotifyWeeklyReset(usage(weekly(90.0)), usage()))
        assertFalse(shouldNotifyWeeklyReset(usage(weekly(null)), usage(weekly(99.0))))
        assertFalse(shouldNotifyWeeklyReset(usage(weekly(90.0)), usage(weekly(null))))
        assertFalse(shouldNotifyWeeklyReset(
            usage(UsageWindow("Weekly", 90.0, null, null, false)),
            usage(weekly(99.0)),
        ))
    }

    @Test fun ignoresFiveHourAndAdditionalWindowIncreases() {
        val previous = usage(weekly(90.0), fiveHour(20.0), additionalWeekly(20.0))
        val current = usage(weekly(90.0), fiveHour(100.0), additionalWeekly(100.0))

        assertFalse(shouldNotifyWeeklyReset(previous, current))
        assertFalse(shouldNotifyWeeklyReset(usage(fiveHour(20.0)), usage(fiveHour(100.0))))
        assertFalse(shouldNotifyWeeklyReset(
            usage(additionalWeekly(20.0)),
            usage(additionalWeekly(100.0)),
        ))
    }

    @Test fun identifiesTheMainWeeklyWindowBySecondsInsteadOfLabel() {
        assertTrue(shouldNotifyWeeklyReset(
            usage(weekly(96.0, label = "Monthly")),
            usage(weekly(98.0, label = "Custom")),
        ))
        assertFalse(shouldNotifyWeeklyReset(
            usage(fiveHour(20.0, label = "Weekly")),
            usage(fiveHour(100.0, label = "Weekly")),
        ))
    }

    private fun usage(vararg windows: UsageWindow) = Usage(null, windows.toList())

    private fun weekly(
        remaining: Double?,
        label: String = "Weekly",
    ) = UsageWindow(label, remaining, null, 604800L, false)

    private fun fiveHour(
        remaining: Double,
        label: String = "5h",
    ) = UsageWindow(label, remaining, null, 18000L, false)

    private fun additionalWeekly(remaining: Double) =
        UsageWindow("Extra · Weekly", remaining, null, 604800L, true)
}
