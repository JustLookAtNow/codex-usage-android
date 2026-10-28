package com.codexusage.android

import android.os.Binder
import android.os.Bundle
import android.os.IInterface
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.github.libxposed.service.XposedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XposedStatusTest {
    private class FakeConnection(
        override val apiVersion: Int = 102,
        override val frameworkName: String = "LSPosed",
        var packages: List<String> = emptyList(),
        var failQuery: Boolean = false
    ) : XposedConnection {
        override val scope: List<String>
            get() {
                if (failQuery) throw IllegalStateException("service disconnected")
                return packages
            }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun awaitText(view: TextView, expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (view.text.toString().contains(expected)) return
            Thread.sleep(10)
        }
        fail("Expected '$expected' in '${view.text}'")
    }

    @Test fun reportsConnectionApiScopeAndQueryErrorsWithoutClaimingBlurIsActive() {
        val absent = readXposedStatus(null)
        assertEquals(XposedStatus.NotConnected, absent)
        assertTrue(xposedStatusText(absent).contains("未检测到可用的模块服务"))
        assertFalse(xposedStatusText(absent).contains("未安装"))

        val old = readXposedStatus(FakeConnection(apiVersion = 101))
        assertEquals(XposedStatus.UnsupportedApi("LSPosed", 101), old)
        assertTrue(xposedStatusText(old).contains("版本不足"))

        val service = FakeConnection()
        val missing = readXposedStatus(service)
        assertEquals(XposedStatus.DesktopNotScoped("LSPosed", 102), missing)
        assertTrue(xposedStatusText(missing).contains("系统桌面未加入作用域"))

        service.packages = listOf("com.android.launcher")
        val scoped = readXposedStatus(service)
        assertEquals(XposedStatus.DesktopScoped("LSPosed", 102), scoped)
        assertTrue(xposedStatusText(scoped).contains("系统桌面已加入作用域"))
        assertTrue(xposedStatusText(scoped).contains("实际模糊效果需在桌面确认"))

        service.failQuery = true
        assertEquals(XposedStatus.QueryFailed, readXposedStatus(service))
    }

    @Test fun trackerNotifiesConnectedAndDisconnectedListeners() {
        val tracker = XposedStatusTracker()
        val service = FakeConnection()
        val changes = mutableListOf<XposedConnection?>()
        val listener: () -> Unit = { changes.add(tracker.connection) }
        tracker.addListener(listener)
        tracker.connect(service)
        tracker.disconnect(service)
        assertEquals(listOf(service, null), changes)
        tracker.removeListener(listener)
        tracker.connect(service)
        assertEquals(2, changes.size)
    }

    @Test fun frameworkProviderDeliversModuleServiceAndScope() {
        val application = RuntimeEnvironment.getApplication() as CodexUsageApplication
        val serviceInterface = Class.forName("io.github.libxposed.service.IXposedService")
        val binder = Binder()
        val service = Proxy.newProxyInstance(serviceInterface.classLoader, arrayOf(serviceInterface)) { proxy, method, args ->
            when (method.name) {
                "getApiVersion" -> 102
                "getFrameworkName" -> "LSPosed"
                "getScope" -> listOf("com.android.launcher")
                "asBinder" -> binder
                "toString" -> "Test Xposed service"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        binder.attachInterface(service as IInterface, serviceInterface.getField("DESCRIPTOR").get(null) as String)
        val provider = Robolectric.buildContentProvider(XposedProvider::class.java).create().get()
        val extras = Bundle().apply { putBinder("binder", binder) }
        provider.call(serviceInterface.getField("SEND_BINDER").get(null) as String, null, extras)

        val connection = application.xposedStatus.connection
        try {
            assertEquals(XposedStatus.DesktopScoped("LSPosed", 102), readXposedStatus(connection))
        } finally {
            if (connection != null) application.xposedStatus.disconnect(connection)
        }
    }

    private fun checkActivityStatusCard() {
        val application = RuntimeEnvironment.getApplication() as CodexUsageApplication
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val service = FakeConnection(packages = listOf("com.android.launcher"))
        val oldService = FakeConnection(apiVersion = 101)
        try {
            val views = descendants(controller.get().window.decorView)
            val texts = views.filterIsInstance<TextView>()
            assertTrue(texts.any { it.text.toString().contains(
                application.getString(R.string.glass_widget_compatibility_description)) })
            assertTrue(texts.any { it.text.toString().contains("未启用时显示半透明背景") })
            val status = texts.single { it.text.toString().startsWith("LSPosed：") }
            val card = status.parent as LinearLayout
            val title = card.getChildAt(0) as TextView
            val heading = card.getChildAt(1) as TextView
            val page = card.parent as ViewGroup
            val refresh = views.filterIsInstance<Button>().single { it.text == "立即刷新" }
            val glassWidget = views.filterIsInstance<Button>().single { it.text == "添加透明玻璃小部件" }
            assertEquals("LSPosed · 要求 API 102+", title.text.toString())
            assertTrue(page.indexOfChild(refresh) < page.indexOfChild(card))
            assertEquals(page.indexOfChild(glassWidget) + 1, page.indexOfChild(card))
            awaitText(heading, "模块服务未连接")
            val attentionBackground = (card.background as GradientDrawable).color!!.defaultColor
            assertTrue(ColorUtils.calculateContrast(heading.currentTextColor, attentionBackground) >= 4.5)
            assertTrue(ColorUtils.calculateContrast(status.currentTextColor, attentionBackground) >= 4.5)

            application.xposedStatus.connect(service)
            awaitText(status, "系统桌面已加入作用域")
            awaitText(heading, "已连接 · 桌面已加入作用域")
            val readyBackground = (card.background as GradientDrawable).color!!.defaultColor
            assertNotEquals(attentionBackground, readyBackground)
            assertTrue(ColorUtils.calculateContrast(heading.currentTextColor, readyBackground) >= 4.5)
            assertTrue(ColorUtils.calculateContrast(status.currentTextColor, readyBackground) >= 4.5)

            controller.pause().stop()
            service.packages = emptyList()
            controller.start().resume()
            awaitText(status, "系统桌面未加入作用域")
            awaitText(heading, "已连接 · 桌面未加入作用域")

            service.failQuery = true
            application.xposedStatus.connect(service)
            awaitText(heading, "状态查询失败")

            application.xposedStatus.connect(oldService)
            awaitText(heading, "API 版本不足")
            awaitText(status, "API 101")

            application.xposedStatus.disconnect(oldService)
            awaitText(status, "未检测到可用的模块服务")
            awaitText(heading, "模块服务未连接")
        } finally {
            application.xposedStatus.disconnect(service)
            application.xposedStatus.disconnect(oldService)
            controller.pause().stop().destroy()
        }
    }

    @Test @Config(qualifiers = "notnight") fun statusCardIsVisibleAndReadableInLightMode() = checkActivityStatusCard()
    @Test @Config(qualifiers = "night") fun statusCardIsVisibleAndReadableInDarkMode() = checkActivityStatusCard()

    @Test fun widgetPickerReceivesCompatibilityDescription() {
        val context = RuntimeEnvironment.getApplication()
        context.resources.getXml(R.xml.glass_usage_widget).use { xml ->
            while (xml.next() != XmlPullParser.START_TAG) Unit
            assertEquals("appwidget-provider", xml.name)
            val description = xml.getAttributeResourceValue(
                "http://schemas.android.com/apk/res/android", "description", 0)
            assertEquals(R.string.glass_widget_compatibility_description, description)
            assertTrue(context.getString(description).contains("ColorOS 17"))
            assertTrue(context.getString(description).contains("LSPosed"))
        }
    }
}
