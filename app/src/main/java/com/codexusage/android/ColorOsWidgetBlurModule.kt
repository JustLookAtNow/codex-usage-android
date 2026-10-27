package com.codexusage.android

import android.appwidget.AppWidgetHostView
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** Optional LSPosed integration: attach ColorOS blur behind this app's glass widget. */
class ColorOsWidgetBlurModule : XposedModule() {
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != LAUNCHER_PACKAGE || !param.isFirstPackage) return
        try {
            val host = Class.forName("com.android.launcher3.widget.CustomLauncherAppWidgetHostView", false, param.classLoader)
            val onLayout = host.getDeclaredMethod("onLayout", Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).apply { isAccessible = true }
            val findTarget = host.getDeclaredMethod("getBlurTargetView", ViewGroup::class.java).apply { isAccessible = true }
            val createBlur = host.getDeclaredMethod("createBlurForView", View::class.java).apply { isAccessible = true }
            val activeTarget = host.getDeclaredField("mOplusBlurBgView").apply { isAccessible = true }
            var unavailable = false
            hook(onLayout).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                if (!unavailable) try {
                    val widget = chain.thisObject as? AppWidgetHostView
                    val provider = widget?.appWidgetInfo?.provider
                    if (widget != null && provider?.packageName == APP_PACKAGE && provider.className == GLASS_PROVIDER) {
                        val target = widget.findViewWithTag<View>(BLUR_TARGET_TAG)
                        if (target != null && target.isShown && target.width > 0 && target.height > 0 &&
                            activeTarget.get(widget) !== target) {
                            val prepared = findTarget.invoke(widget, widget)
                            if (prepared === target) createBlur.invoke(widget, target)
                        }
                    }
                } catch (error: Throwable) {
                    unavailable = true
                    log(Log.WARN, TAG, "ColorOS widget blur is unavailable", error)
                }
                result
            }
            log(Log.INFO, TAG, "ColorOS glass widget blur integration enabled")
        } catch (error: Throwable) {
            // ColorOS internal methods may change; the regular translucent widget still works.
            log(Log.WARN, TAG, "ColorOS widget blur is unavailable", error)
        }
    }

    companion object {
        private const val TAG = "CodexWidgetBlur"
        private const val LAUNCHER_PACKAGE = "com.android.launcher"
        private const val APP_PACKAGE = "com.codexusage.android"
        private const val GLASS_PROVIDER = "com.codexusage.android.GlassUsageWidget"
        private const val BLUR_TARGET_TAG = "OplusBlurBg"
    }
}
