package com.codexusage.android

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetSchedulingTest {
    private lateinit var context: Context

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().clear().commit()
        UsageRepository(context).clear()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test fun schedulingIsUniqueAndClearCancelsPeriodicAndManualWork() {
        val manager = WorkManager.getInstance(context)
        RefreshWorker.schedule(context)
        RefreshWorker.now(context)
        assertTrue(manager.getWorkInfosForUniqueWork("quota-periodic").get().isEmpty())
        assertTrue(manager.getWorkInfosForUniqueWork("quota-manual").get().isEmpty())
        // An intentionally unsupported mode avoids any real credentials or network requests.
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().putString("mode", "test").commit()
        RefreshWorker.schedule(context)
        RefreshWorker.schedule(context)
        val work = manager.getWorkInfosForUniqueWork("quota-periodic").get()
        assertEquals(1, work.size)
        assertEquals(TimeUnit.MINUTES.toMillis(15), work.single().periodicityInfo!!.repeatIntervalMillis)
        RefreshWorker.now(context)
        RefreshWorker.cancel(context)
        CredentialStore(context).clear()
        UsageRepository(context).clear()
        RefreshWorker.schedule(context)
        RefreshWorker.now(context)
        assertTrue(manager.getWorkInfosForUniqueWork("quota-periodic").get().all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(manager.getWorkInfosForUniqueWork("quota-manual").get().all { it.state.isFinished })
        assertNull(UsageRepository(context).error)
    }

    @Test fun widgetRetainsCachedQuotaWithErrorsAndClearsAllOldQuota() {
        val manager = shadowOf(AppWidgetManager.getInstance(context))
        val id = manager.createWidget(UsageWidget::class.java, R.layout.usage_widget)
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).edit().putString("mode", "test").commit()
        val usage = UsageParser.parse("""{"rate_limit":{"primary_window":{"used_percent":25,"limit_window_seconds":18000,"reset_at":1800000000},"secondary_window":{"used_percent":50,"limit_window_seconds":604800,"reset_at":1800000000}}}""")
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit()
            .putString("body", UsageParser.displayJson(usage)).putLong("updated_at", 1800000000000)
            .putString("error", "网络连接失败").commit()
        UsageWidget.updateAll(context)
        var view = manager.getViewFor(id)
        assertEquals("75%", view.findViewById<TextView>(R.id.widget_primary_value).text.toString())
        assertEquals("50%", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
        val status = view.findViewById<TextView>(R.id.widget_status).text.toString()
        assertTrue(status.contains("缓存"))
        val description = view.findViewById<TextView>(R.id.widget_status).contentDescription.toString()
        assertTrue(description.contains("更新于"))
        assertTrue(description.contains("旧数据"))
        assertEquals("上次刷新失败，点击重试", view.findViewById<android.view.View>(R.id.widget_refresh).contentDescription)
        assertEquals(R.drawable.ic_widget_refresh_warning, shadowOf(view.findViewById<ImageView>(R.id.widget_refresh_icon).drawable).createdFromResId)
        assertFalse(view.findViewById<TextView>(R.id.widget_primary_countdown).text.contains("重置"))
        context.getSharedPreferences("usage", Context.MODE_PRIVATE).edit().remove("error").commit()
        UsageWidget.updateAll(context)
        view = manager.getViewFor(id)
        assertEquals("刷新额度", view.findViewById<android.view.View>(R.id.widget_refresh).contentDescription)
        assertEquals(R.drawable.ic_widget_refresh, shadowOf(view.findViewById<ImageView>(R.id.widget_refresh_icon).drawable).createdFromResId)
        CredentialStore(context).clear()
        UsageRepository(context).clear()
        UsageWidget.updateAll(context)
        view = manager.getViewFor(id)
        assertEquals("—", view.findViewById<TextView>(R.id.widget_primary_value).text.toString())
        assertEquals("—", view.findViewById<TextView>(R.id.widget_weekly_value).text.toString())
        assertEquals("—", view.findViewById<TextView>(R.id.widget_primary_countdown).text.toString())
        assertEquals("请配置", view.findViewById<TextView>(R.id.widget_status).text.toString())
        view.findViewById<android.view.View>(R.id.widget_refresh).performClick()
        assertEquals(MainActivity::class.java.name, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.component!!.className)
    }
}
