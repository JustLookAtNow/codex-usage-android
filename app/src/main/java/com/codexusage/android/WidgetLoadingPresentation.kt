package com.codexusage.android

import android.content.Context
import android.view.View
import android.widget.RemoteViews

internal object WidgetLoadingPresentation {
    fun apply(context: Context, views: RemoteViews, refreshing: Boolean, style: WidgetStyle = WidgetStyle.STANDARD) {
        // Reset old loading views when launchers reapply the same RemoteViews tree.
        views.setViewVisibility(R.id.widget_content, View.VISIBLE)
        views.setViewVisibility(R.id.widget_loading_overlay, View.GONE)
        views.setImageViewResource(R.id.widget_loading_overlay, 0)
        views.setViewVisibility(R.id.widget_refresh_icon, if (refreshing) View.INVISIBLE else View.VISIBLE)
        views.removeAllViews(R.id.widget_refresh_animation)
        if (refreshing) {
            views.addView(R.id.widget_refresh_animation, RemoteViews(context.packageName,
                if (style == WidgetStyle.GLASS) R.layout.widget_glass_refresh_spinner else R.layout.widget_refresh_spinner))
        }
    }
}
