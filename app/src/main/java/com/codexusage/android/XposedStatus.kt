package com.codexusage.android

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

private const val REQUIRED_XPOSED_API = 102
private const val LAUNCHER_PACKAGE = "com.android.launcher"

internal interface XposedConnection {
    val apiVersion: Int
    val frameworkName: String
    val scope: List<String>
}

private class FrameworkConnection(val service: XposedService) : XposedConnection {
    override val apiVersion: Int get() = service.apiVersion
    override val frameworkName: String get() = service.frameworkName
    override val scope: List<String> get() = service.scope
}

internal sealed interface XposedStatus {
    data object NotConnected : XposedStatus
    data object QueryFailed : XposedStatus
    data class UnsupportedApi(val framework: String, val api: Int) : XposedStatus
    data class DesktopNotScoped(val framework: String, val api: Int) : XposedStatus
    data class DesktopScoped(val framework: String, val api: Int) : XposedStatus
}

internal fun readXposedStatus(connection: XposedConnection?): XposedStatus {
    if (connection == null) return XposedStatus.NotConnected
    return try {
        val api = connection.apiVersion
        val framework = connection.frameworkName
        when {
            api < REQUIRED_XPOSED_API -> XposedStatus.UnsupportedApi(framework, api)
            LAUNCHER_PACKAGE !in connection.scope -> XposedStatus.DesktopNotScoped(framework, api)
            else -> XposedStatus.DesktopScoped(framework, api)
        }
    } catch (_: Exception) {
        XposedStatus.QueryFailed
    }
}

internal fun xposedStatusText(status: XposedStatus): String = when (status) {
    XposedStatus.NotConnected -> "LSPosed：未检测到可用的模块服务。请确认已安装并启用本应用模块；必要时重新打开应用。"
    XposedStatus.QueryFailed -> "LSPosed：模块服务状态查询失败，请重新打开应用或检查 LSPosed。"
    is XposedStatus.UnsupportedApi -> "${status.framework}：API ${status.api}，版本不足；请使用支持 API 102 的 LSPosed。"
    is XposedStatus.DesktopNotScoped -> "${status.framework}（API ${status.api}）：模块服务已连接，系统桌面未加入作用域。请在 LSPosed 中勾选系统桌面。"
    is XposedStatus.DesktopScoped -> "${status.framework}（API ${status.api}）：模块服务已连接，系统桌面已加入作用域。启用或更新模块后请重启桌面；实际模糊效果需在桌面确认。"
}

internal class XposedStatusTracker {
    @Volatile var connection: XposedConnection? = null
        private set
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun connect(connection: XposedConnection) {
        this.connection = connection
        listeners.forEach { it() }
    }

    fun disconnect(connection: XposedConnection) {
        if (this.connection !== connection) return
        this.connection = null
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
}

class CodexUsageApplication : Application(), XposedServiceHelper.OnServiceListener {
    internal val xposedStatus = XposedStatusTracker()

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedStatus.connect(FrameworkConnection(service))
    }

    override fun onServiceDied(service: XposedService) {
        val connection = xposedStatus.connection as? FrameworkConnection ?: return
        if (connection.service === service) xposedStatus.disconnect(connection)
    }
}
