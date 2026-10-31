package com.codexusage.android

import android.content.Context
import org.json.JSONArray

internal enum class ResetExpiryLevel { NORMAL, WARNING, CRITICAL }

internal fun ResetCredit.expiryLevel(nowSeconds: Long): ResetExpiryLevel {
    if (status != "available") return ResetExpiryLevel.NORMAL
    val remaining = (expiresAt ?: return ResetExpiryLevel.NORMAL) - nowSeconds
    return when {
        remaining <= 0 -> ResetExpiryLevel.NORMAL
        remaining <= 24 * 3600 -> ResetExpiryLevel.CRITICAL
        remaining <= 72 * 3600 -> ResetExpiryLevel.WARNING
        else -> ResetExpiryLevel.NORMAL
    }
}

internal fun ResetCredits?.expiryLevel(nowSeconds: Long): ResetExpiryLevel =
    if (this == null || availableCount <= 0) ResetExpiryLevel.NORMAL
    else credits.orEmpty().maxOfOrNull { it.expiryLevel(nowSeconds) } ?: ResetExpiryLevel.NORMAL

internal fun resetExpiryColor(
    context: Context, level: ResetExpiryLevel, normalColor: Int, glass: Boolean = false
): Int = when (level) {
    ResetExpiryLevel.NORMAL -> normalColor
    ResetExpiryLevel.WARNING -> context.getColor(if (glass) R.color.widget_glass_warning else R.color.quota_warning)
    ResetExpiryLevel.CRITICAL -> context.getColor(if (glass) R.color.widget_glass_critical else R.color.quota_critical)
}

internal fun ResetCredit.expiryNotificationKey(): String = id?.let { "id:$it" }
    ?: "fields:${JSONArray().put(grantedAt).put(expiresAt).put(resetType)}"

internal fun resetCountLabel(count: Int?): String = when (count) {
    null -> "— resets"
    1 -> "1 reset"
    else -> "$count resets"
}
