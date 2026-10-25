package com.codexusage.android

import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
    @Test fun countdownUsesCeilingMinutesAndNeverBecomesNegative() {
        val now = 1800000000L
        fun after(seconds: Long) = recoveryCountdown(now + seconds, now)
        assertEquals("—", recoveryCountdown(null, now))
        assertEquals("0分钟", after(-600))
        assertEquals("0分钟", after(0))
        assertEquals("1分钟", after(1))
        assertEquals("1分钟", after(60))
        assertEquals("2分钟", after(61))
        assertEquals("59分钟", after(3540))
        assertEquals("1小时0分", after(3599))
        assertEquals("1小时0分", after(3600))
        assertEquals("2小时55分", after(10500))
        assertEquals("1天0小时", after(86400))
        assertEquals("6天8小时", after(6 * 86400 + 8 * 3600))
        // A delayed update recalculates from the deadline instead of decrementing a counter.
        assertEquals("2小时45分", recoveryCountdown(now + 10500, now + 600))
        assertEquals("0分钟", recoveryCountdown(now + 60, now + 7200))
    }

    @Test fun sizesSwitchAtSharedResponsiveBreakpoints() {
        assertEquals(WidgetSize.COMPACT, widgetSize(180, 80))
        assertEquals(WidgetSize.COMPACT, widgetSize(239, 180))
        assertEquals(WidgetSize.WIDE, widgetSize(240, 80))
        assertEquals(WidgetSize.WIDE, widgetSize(360, 139))
        assertEquals(WidgetSize.LARGE, widgetSize(240, 140))
        assertEquals(WidgetSize.LARGE, widgetSize(360, 180))
    }

    @Test fun weeklyLayoutUsesWindowPresenceRatherThanPlanPositionOrPercentage() {
        val weekly = UsageWindow("Weekly", null, null, 604800, false)
        val five = UsageWindow("5h", null, null, 18000, false)
        val extra = five.copy(label = "Model · 5h", additional = true)
        assertTrue(weeklyOnly(widgetWindows(Usage("plus", listOf(weekly, extra)))))
        assertTrue(weeklyOnly(widgetWindows(Usage("team", listOf(weekly)))))
        assertFalse(weeklyOnly(widgetWindows(Usage(null, listOf(weekly, five)))))
        assertFalse(weeklyOnly(widgetWindows(Usage(null, listOf(five)))))
        assertFalse(weeklyOnly(widgetWindows(Usage(null, listOf(extra)))))
        assertFalse(weeklyOnly(widgetWindows(null)))
    }

    @Test fun periodsStayInCorrectSlotsWhenReorderedOrMissingAndExcludeExtras() {
        val five = UsageWindow("5h", 51.0, 10L, 18000, false)
        val weekly = UsageWindow("Weekly", 90.0, 20L, 604800, false)
        val extra = UsageWindow("Model · 5h", 100.0, 30L, 18000, true)
        assertEquals(listOf(five, weekly), widgetWindows(Usage(null, listOf(extra, weekly, five))))
        assertEquals(listOf(null, weekly), widgetWindows(Usage(null, listOf(extra, weekly))))
        assertEquals(listOf(null, null), widgetWindows(null))
    }
}
