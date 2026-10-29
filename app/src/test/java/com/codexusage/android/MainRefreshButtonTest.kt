package com.codexusage.android

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainRefreshButtonTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context).clear()
        WidgetRefreshState.clear(context)
    }

    @Test fun unconfiguredRefreshShowsHintWithoutStartingLoading() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val refresh = refreshButton(activity.window.decorView)
            assertAnimatedIcon(refresh)

            refresh.performClick()
            idleUi()

            assertIdle(refresh)
            assertFalse(WidgetRefreshState.active(context))
            assertTrue(texts(activity.window.decorView).any { it.text.toString() == "请先配置凭据" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun startUpdatesRefreshButtonAndUsageRenderKeepsItDisabledUntilClear() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val refresh = refreshButton(activity.window.decorView)

            WidgetRefreshState.start(context)
            idleUi()

            assertRefreshing(refresh)
            assertTrue(
                "其他操作不应随刷新按钮一起禁用",
                buttons(activity.window.decorView).filter { it !== refresh }.all { it.isEnabled }
            )

            val renderError = "测试渲染时的查询错误"
            context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit()
                .putString("error", renderError).commit()
            idleUi()

            assertRefreshing(refresh)

            WidgetRefreshState.clear(context)
            idleUi()

            assertIdle(refresh)
            assertTrue(texts(activity.window.decorView).any { it.text.toString() == renderError })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun activeRefreshRemainsDisabledAfterActivityRecreation() {
        WidgetRefreshState.start(context)
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertRefreshing(refreshButton(first.get().window.decorView))
        } finally {
            first.pause().stop().destroy()
        }

        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertRefreshing(refreshButton(second.get().window.decorView))
            assertTrue(WidgetRefreshState.active(context))
        } finally {
            second.pause().stop().destroy()
        }
    }

    @Test fun widgetRefreshListenerStopsUpdatingDestroyedActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val refresh = refreshButton(controller.get().window.decorView)
        try {
            assertIdle(refresh)
        } finally {
            controller.pause().stop().destroy()
        }

        WidgetRefreshState.start(context)
        idleUi()

        assertIdle(refresh)
    }

    private fun refreshButton(view: View): Button = buttons(view).single {
        it.text == "立即刷新" || it.text == "正在刷新…"
    }

    private fun assertAnimatedIcon(button: Button) {
        val icons = button.compoundDrawablesRelative.filterNotNull() + button.compoundDrawables.filterNotNull()
        assertTrue("立即刷新按钮应包含 AnimatedVectorDrawable 刷新图标", icons.any { it is AnimatedVectorDrawable })
    }

    private fun assertIdle(button: Button) {
        assertEquals("立即刷新", button.text.toString())
        assertTrue(button.isEnabled)
    }

    private fun assertRefreshing(button: Button) {
        assertEquals("正在刷新…", button.text.toString())
        assertFalse(button.isEnabled)
        assertTrue(WidgetRefreshState.active(button.context))
    }

    private fun idleUi() = shadowOf(Looper.getMainLooper()).idle()

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
}
