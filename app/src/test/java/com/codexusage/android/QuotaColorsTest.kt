package com.codexusage.android

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuotaColorsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context).clear()
        WidgetRefreshState.clear(context)
    }

    @Test fun thresholdsIncludeBoundariesAndUseUnroundedRemainingQuota() {
        val normal = context.getColor(R.color.app_accent)
        val warning = context.getColor(R.color.quota_warning)
        val critical = context.getColor(R.color.quota_critical)
        for (value in listOf(0.0, 14.9999, 15.0)) assertEquals(critical, quotaColor(context, value, normal))
        for (value in listOf(15.0001, 39.9999, 40.0)) assertEquals(warning, quotaColor(context, value, normal))
        for (value in listOf(null, 40.0001, 100.0)) assertEquals(normal, quotaColor(context, value, normal))
    }

    private fun checkPageColors() {
        val initial = Usage("test", listOf(
            UsageWindow("5h", 15.0, null, 18000, false),
            UsageWindow("Weekly", 40.0, null, 604800, false),
            UsageWindow("Extra · 5h", 20.0, null, 18000, true)
        ))
        val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
        prefs.edit().putString("body", UsageParser.displayJson(initial)).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            fun descendants(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val quota = descendants(activity.window.decorView).filterIsInstance<TextView>()
                .single { it.text.startsWith("5h   ") }
            fun check(expected: List<Int>) {
                val text = quota.text as Spanned
                val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
                    .sortedBy { text.getSpanStart(it) }
                assertEquals(expected, spans.map { it.foregroundColor })
                for (span in spans) {
                    assertTrue(text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).endsWith("%") ||
                        text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString() == "—")
                }
            }
            val warning = activity.getColor(R.color.quota_warning)
            val critical = activity.getColor(R.color.quota_critical)
            val normal = activity.getColor(R.color.app_accent)
            check(listOf(critical, warning, warning))
            for (color in listOf(warning, critical)) {
                for (surface in listOf(R.color.app_surface, R.color.widget_surface)) {
                    assertTrue("Warning text must remain readable", ColorUtils.calculateContrast(color, activity.getColor(surface)) >= 4.5)
                }
            }
            val recovered = initial.copy(windows = initial.windows.mapIndexed { index, window ->
                window.copy(remaining = if (index == 1) null else 80.0)
            })
            prefs.edit().putString("body", UsageParser.displayJson(recovered)).commit()
            check(listOf(normal, normal, normal))
            assertTrue(quota.text.contains("Weekly   —"))
            prefs.edit().remove("body").commit()
            assertEquals("5h  —\n\nWeekly  —", quota.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test @Config(qualifiers = "notnight") fun lightPageColorsEachQuotaAndRestoresNormalColors() = checkPageColors()
    @Test @Config(qualifiers = "night") fun darkPageColorsEachQuotaAndRestoresNormalColors() = checkPageColors()
}
