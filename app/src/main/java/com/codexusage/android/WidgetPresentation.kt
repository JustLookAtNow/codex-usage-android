package com.codexusage.android

// Shared breakpoints for Android 12+ RemoteViews and the legacy resize callback.
internal enum class WidgetSize(val width: Float, val height: Float) {
    COMPACT(140f, 64f), WIDE(240f, 64f), LARGE(240f, 140f)
}

internal enum class WidgetStyle { STANDARD, GLASS }

internal fun widgetSize(width: Int, height: Int): WidgetSize = when {
    width >= 240 && height >= 140 -> WidgetSize.LARGE
    width >= 240 -> WidgetSize.WIDE
    else -> WidgetSize.COMPACT
}

internal fun widgetWindows(usage: Usage?): List<UsageWindow?> {
    val main = usage?.windows.orEmpty().filterNot { it.additional }
    return listOf(main.firstOrNull { it.seconds == 18000L || it.label == "5h" },
        main.firstOrNull { it.seconds == 604800L || it.label == "Weekly" })
}

internal fun weeklyOnly(windows: List<UsageWindow?>): Boolean =
    windows.getOrNull(0) == null && windows.getOrNull(1) != null

internal fun recoveryCountdown(resetAt: Long?, nowSeconds: Long): String {
    if (resetAt == null) return "—"
    val seconds = (resetAt - nowSeconds).coerceAtLeast(0)
    if (seconds == 0L) return "0分钟"
    if (seconds >= 86400) return "${seconds / 86400}天${seconds % 86400 / 3600}小时"
    val minutes = (seconds + 59) / 60
    return if (minutes < 60) "${minutes}分钟" else "${minutes / 60}小时${minutes % 60}分"
}
