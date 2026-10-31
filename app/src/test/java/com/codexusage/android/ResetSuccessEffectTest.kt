package com.codexusage.android

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.Button
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.work.WorkManager
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
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ResetSuccessEffectTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val before = Usage("test", listOf(
        UsageWindow("5h", 20.0, 1800000000, 18000, false),
        UsageWindow("Weekly", 40.0, null, 604800, false),
        UsageWindow("Extra", null, null, 18000, true)))
    private val target get() = before.copy(windows = before.windows.map { it.copy(remaining = 100.0) })

    private fun durationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Any>(ValueAnimator::class.java, "setDurationScale",
            ClassParameter.from(java.lang.Float.TYPE, scale))
    }

    @Before fun setup() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context).clear()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        WidgetRefreshState.clear(context)
        durationScale(1f)
    }

    @Test fun interpolationMatchesWindowsAndKeepsMetadataWithUnknownValuesImmediatelyFull() {
        val halfway = resetAnimationUsage(before, target.copy(windows = target.windows.reversed()), 0.5f)
        assertEquals(listOf(100.0, 70.0, 60.0), halfway.windows.map { it.remaining })
        assertEquals(target.windows.reversed().map { it.resetAt }, halfway.windows.map { it.resetAt })
        assertEquals(listOf(100.0, 100.0, 100.0), resetAnimationUsage(before, target, 1f).windows.map { it.remaining })
        assertEquals(target, resetAnimationUsage(null, target, 0f))
    }

    @Test fun numbersReachFullAfterOneSecondAndFireworksAreRemovedAfterTwoSeconds() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val root = FrameLayout(controller.get())
            controller.get().setContentView(root)
            val effect = ResetSuccessEffect(root)
            var frame: Usage? = null
            var finished = 0
            effect.start(before, target, { frame = it }, { finished++ })
            assertTrue(effect.running)
            assertEquals(1, root.childCount)
            assertFalse(root.getChildAt(0).isClickable)
            val animator = ReflectionHelpers.getField<ValueAnimator>(effect, "animator")
            assertEquals(2000L, animator.duration)
            animator.currentPlayTime = 500
            assertEquals(listOf(60.0, 70.0, 100.0), frame!!.windows.map { it.remaining })
            animator.currentPlayTime = 1000
            assertEquals(target, frame)
            animator.end()
            assertEquals(1, finished)
            assertFalse(effect.running)
            assertEquals(0, root.childCount)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun stoppingReleasesOverlayAndAnimatorWithoutCallingCompletion() {
        val root = FrameLayout(context)
        val effect = ResetSuccessEffect(root)
        var finished = false
        effect.start(before, target, {}, { finished = true })
        val animator = ReflectionHelpers.getField<ValueAnimator>(effect, "animator")
        effect.stop()
        assertEquals(0, root.childCount)
        assertFalse(effect.running)
        assertFalse(animator.isRunning)
        assertFalse(finished)
    }

    @Test fun disabledAnimationsFinishImmediatelyWithoutOverlay() {
        durationScale(0f)
        try {
            val root = FrameLayout(context)
            val effect = ResetSuccessEffect(root)
            var finished = false
            effect.start(before, target, { fail("No animated frames when disabled") }, { finished = true })
            assertTrue(finished)
            assertFalse(effect.running)
            assertEquals(0, root.childCount)
        } finally { durationScale(1f) }
    }

    private fun saveSuccess(id: String) {
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit()
            .putString("body", UsageParser.displayJson(before))
            .putBoolean("reset_display_full", true)
            .putString("reset_success_id", id)
            .putString("reset_success_before", UsageParser.displayJson(before))
            .putString("reset_message", ResetResult.RESET.message).commit()
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun activityPauseFinishesAtFullValuesAndRecreationDoesNotReplaySuccess() {
        saveSuccess("first-reset")
        // Attach the window before resume so the test can inspect the first animation frame.
        val first = Robolectric.buildActivity(MainActivity::class.java).create().start().visible().resume()
        try {
            val views = descendants(first.get().window.decorView)
            assertEquals(1, views.filterIsInstance<ResetFireworksView>().size)
            assertNull(UsageRepository(context).claimResetCelebration())
            first.pause()
            assertTrue(descendants(first.get().window.decorView).none { it is ResetFireworksView })
            val quota = descendants(first.get().window.decorView).filterIsInstance<TextView>()
                .single { it.text.contains("套餐 test") }
            assertEquals(3, Regex("100%").findAll(quota.text).count())
            first.stop().destroy()
            val second = Robolectric.buildActivity(MainActivity::class.java).setup()
            try {
                assertTrue(descendants(second.get().window.decorView).none { it is ResetFireworksView })
                assertNull(UsageRepository(context).claimResetCelebration())
            } finally { second.pause().stop().destroy() }
        } finally {
            if (!first.get().isDestroyed) first.stop().destroy()
        }
    }

    @Test fun reopeningWithLegacyPendingStateSchedulesReadonlyRefresh() {
        // Missing imported credentials fail locally; this test never connects to a real account.
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit()
            .putString("mode", "import").commit()
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit()
            .putString("reset_request_id", "old-version-request")
            .putString("reset_credit_id", "selected")
            .putString("reset_message", "连接超时，请稍后刷新").commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("quota-manual").get()
            assertEquals(1, work.size)
            assertTrue(UsageRepository(context).resetPending)
            assertNull(UsageRepository(context).claimResetCelebration())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun userCanConfirmHistoricalSuccessWithoutChangingTheNewCreditOrPlayingAnAnimation() {
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit()
            .putString("body", UsageParser.displayJson(before))
            .putString("reset_request_id", "historical-request")
            .putString("reset_credit_id", "old-credit")
            .putString("reset_message", "连接超时，请稍后刷新")
            .putInt("credits", 1).putLong("credits_updated_at", System.currentTimeMillis())
            .putString("credits_body", """{"available_count":1,"credits":[{"id":"new-credit","status":"available"}]}""")
            .commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            fun buttons() = descendants(activity.window.decorView).filterIsInstance<Button>()
            buttons().single { it.text == "展开重置点详情 ▾" }.performClick()
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text.contains("刷新后将自动核对并确认结果") })
            buttons().single { it.text == "确认上次已完成" }.performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(UsageRepository(context).resetPending)
            buttons().single { it.text == "确认上次已完成" }.performClick()
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            ReflectionHelpers.getField<ExecutorService>(activity, "executor").submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(UsageRepository(context).resetPending)
            assertEquals(1, UsageRepository(context).credits)
            assertEquals("new-credit", UsageRepository(context).resetCredits()!!.credits!!.single().id)
            assertEquals(UsageParser.parse(UsageParser.displayJson(before)), UsageRepository(context).displayUsage())
            assertTrue(buttons().none { it.text == "重试本次重置" || it.text == "确认上次已完成" })
            assertTrue(descendants(activity.window.decorView).none { it is ResetFireworksView })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun eventConfirmedWhilePausedIsPlayedOnNextResume() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            controller.pause()
            saveSuccess("background-reset")
            assertTrue(descendants(controller.get().window.decorView).none { it is ResetFireworksView })
            controller.resume()
            assertEquals(1, descendants(controller.get().window.decorView).filterIsInstance<ResetFireworksView>().size)
            assertNull(UsageRepository(context).claimResetCelebration())
        } finally { controller.pause().stop().destroy() }
    }
}
