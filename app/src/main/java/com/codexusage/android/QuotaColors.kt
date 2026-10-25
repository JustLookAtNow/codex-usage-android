package com.codexusage.android

import android.content.Context

// Compare the original remaining percentage, before display rounding.
internal fun quotaColor(context: Context, remaining: Double?, normalColor: Int): Int = when {
    remaining == null -> normalColor
    remaining <= 15.0 -> context.getColor(R.color.quota_critical)
    remaining <= 40.0 -> context.getColor(R.color.quota_warning)
    else -> normalColor
}
