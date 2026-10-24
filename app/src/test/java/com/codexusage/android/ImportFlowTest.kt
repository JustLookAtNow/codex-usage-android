package com.codexusage.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImportFlowTest {
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
}
