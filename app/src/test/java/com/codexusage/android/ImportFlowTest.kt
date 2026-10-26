package com.codexusage.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.app.AlertDialog
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportFlowTest {
    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun pickerUsesOpenDocumentAndCancellationLeavesConfigurationIntact() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context).clear()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val actions = buttons(activity.window.decorView)
            val button = actions.single { it.text == "手工导入 auth.json" }
            button.performClick()
            val started = shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
            assertTrue(started.intent.categories.contains(Intent.CATEGORY_OPENABLE))
            assertTrue(actions.all { !it.isEnabled })
            shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
            assertTrue(actions.all { it.isEnabled })
            assertNull(CredentialStore(activity).mode)
            assertNull(UsageRepository(activity).body)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun resetDetailsStartCollapsedAndEachCreditHasItsOwnConfirmedAction() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().putString("mode", "test").commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        UsageRepository(context).clear()
        val prefs = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
        prefs.edit().putInt("credits", 2).putString("credits_body", """{"available_count":2,"credits":[{
            "id":"test-credit","reset_type":"codex_rate_limits","status":"available",
            "granted_at":"2026-10-24T00:00:00Z","expires_at":"2026-11-24T00:00:00Z",
            "title":"Test reset","description":"Weekly + 5h"},{"id":"second-credit","status":"available",
            "title":"Second reset","expires_at":"2026-12-24T00:00:00Z"}]}""").commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val summary = texts(activity.window.decorView).single { it.text.startsWith("重置点 2 个") }
            assertTrue(summary.isShown)
            assertTrue(summary.text.contains("2026-11-"))
            assertTrue(summary.text.contains("2026-12-"))
            assertFalse(summary.text.contains("编号"))
            val detailView = texts(activity.window.decorView).single { it.text.contains("编号：test-credit") }
            assertFalse(detailView.isShown)
            val toggle = buttons(activity.window.decorView).single { it.text == "展开重置点详情 ▾" }
            toggle.performClick()
            assertTrue(detailView.isShown)
            val details = detailView.text
            assertTrue(details.contains("状态：可用"))
            assertTrue(details.contains("发放：2026-10-"))
            assertTrue(details.contains("到期：2026-11-"))
            assertTrue(details.contains("说明：Weekly + 5h"))
            val uses = buttons(activity.window.decorView).filter { it.text == "使用" }
            assertEquals(2, uses.size)
            assertTrue(uses.all { it.isShown && it.isEnabled })
            val reset = uses[1]
            assertTrue(texts(reset.parent as View).any { it.text.contains("编号：second-credit") })
            assertTrue(reset.isEnabled)
            reset.performClick()
            assertFalse(reset.isEnabled)
            assertFalse(UsageRepository(context).resetPending)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(shadowOf(dialog).message.contains("Second reset"))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(reset.isEnabled)
            assertFalse(UsageRepository(context).resetPending)
            assertEquals(2, UsageRepository(context).credits)
            toggle.performClick()
            assertFalse(detailView.isShown)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
