package com.codexusage.android

import android.content.Context
import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResetCreditExpiryUiTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun credit(id: String, remaining: Long?, status: String = "available") = ResetCredit(
        id, "codex_rate_limits", status, null, remaining?.let { System.currentTimeMillis() / 1000 + it }, id, "说明内容")
    private fun save(rows: List<ResetCredit>) {
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().putInt("credits", rows.size)
            .putString("credits_body", ResetCreditsParser.displayJson(ResetCredits(rows.size, null, rows))).commit()
    }

    @Before fun setup() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().putString("mode", "test").commit()
        UsageRepository(context).clear()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test @Config(qualifiers = "notnight") fun detailsAndSummaryUseDayColors() = verifyColors()
    @Test @Config(qualifiers = "night") fun detailsAndSummaryUseNightColors() = verifyColors()

    private fun verifyColors() {
        save(listOf(credit("warning", 48 * 3600), credit("critical", 3600), credit("expired", 0),
            credit("unknown", null), credit("used", 3600, "consumed")))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            texts(root).filterIsInstance<Button>().single { it.text == "展开重置点详情 ▾" }.performClick()
            val summary = texts(root).single { it.text.startsWith("重置点 5 个") }
            assertEquals(activity.getColor(R.color.quota_critical), summary.currentTextColor)
            for ((id, color) in listOf("warning" to R.color.quota_warning, "critical" to R.color.quota_critical,
                "expired" to null, "unknown" to null, "used" to null)) {
                val row = texts(root).single { it.text.contains("编号：$id") }
                val rich = row.text as Spanned
                val spans = rich.getSpans(0, rich.length, ForegroundColorSpan::class.java)
                assertEquals(id, if (color == null) 0 else 1, spans.size)
                if (color != null) {
                    assertEquals(activity.getColor(color), spans.single().foregroundColor)
                    val colored = rich.subSequence(rich.getSpanStart(spans.single()), rich.getSpanEnd(spans.single())).toString()
                    assertTrue(colored.startsWith("到期："))
                    assertFalse(colored.contains('\n'))
                }
            }
            save(listOf(credit("normal", 80 * 3600)))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(activity.getColor(R.color.app_accent), summary.currentTextColor)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun foregroundMinuteTickRebuildsExpiryPresentationAndStopsWhilePaused() {
        save(listOf(credit("boundary", 48 * 3600)))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            val summary = texts(root).single { it.text.startsWith("重置点 1 个") }
            assertEquals(activity.getColor(R.color.quota_warning), summary.currentTextColor)
            val initialRow = texts(root).single { it.text.contains("编号：boundary") }
            shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)
            // Looper time advances independently of Java's wall clock; verify a local redraw.
            assertNotSame(initialRow, texts(root).single { it.text.contains("编号：boundary") })
            controller.pause()
            val row = texts(root).single { it.text.contains("编号：boundary") }
            shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS)
            assertSame(row, texts(root).single { it.text.contains("编号：boundary") })
            controller.resume()
        } finally { controller.pause().stop().destroy() }
    }
}
