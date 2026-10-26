package com.codexusage.android

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
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
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppThemeTest {
    @Before fun reset() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context).clear()
        WidgetRefreshState.clear(context)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun checkTheme(dark: Boolean) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val views = descendants(activity.window.decorView)
            val background = (views.filterIsInstance<ScrollView>().single().background as ColorDrawable).color
            val quota = views.filterIsInstance<TextView>().single { it.text.toString() == "5h  —\n\nWeekly  —" }
            val surface = ((quota.parent as View).background as GradientDrawable).color!!.defaultColor
            assertEquals(activity.getColor(R.color.app_background), background)
            assertEquals(activity.getColor(R.color.app_surface), surface)
            assertTrue(ColorUtils.calculateContrast(quota.currentTextColor, surface) >= 4.5)
            if (dark) {
                assertTrue(ColorUtils.calculateLuminance(background) < 0.05)
                assertTrue(ColorUtils.calculateLuminance(surface) < 0.05)
            } else {
                assertEquals(Color.WHITE, surface)
                assertTrue(ColorUtils.calculateLuminance(background) > 0.8)
            }
            // Native dialogs and path inputs must inherit the same theme as the page.
            views.filterIsInstance<Button>().single { it.text == "Root 自定义路径" }.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val theme = dialog.context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
            try {
                val luminance = ColorUtils.calculateLuminance(theme.getColor(0, Color.WHITE))
                assertTrue("Dialog must follow the page theme", if (dark) luminance < 0.2 else luminance > 0.8)
            } finally { theme.recycle() }
            dialog.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test @Config(qualifiers = "notnight") fun lightPageAndDialogsRemainLight() = checkTheme(false)
    @Test @Config(qualifiers = "night") fun darkPageAndDialogsUseDarkSurfacesAndReadableText() = checkTheme(true)
}
