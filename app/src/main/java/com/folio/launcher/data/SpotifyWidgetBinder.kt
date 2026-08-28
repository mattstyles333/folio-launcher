package com.folio.launcher.data

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import kotlin.math.roundToInt

class SpotifyWidgetBinder(private val app: Context) {
    private val host = AppWidgetHost(app, HOST_ID)
    private val mgr = AppWidgetManager.getInstance(app)

    fun start() {
        runCatching { host.startListening() }
    }

    fun stop() {
        runCatching { host.stopListening() }
    }

    fun provider(): AppWidgetProviderInfo? {
        val list = runCatching { mgr.installedProviders }.getOrDefault(emptyList())
            .filter { it.provider.packageName.contains("spotify", ignoreCase = true) }
        if (list.isEmpty()) return null
        val pm = app.packageManager
        val named = list.firstOrNull { info ->
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
            label.contains("now", ignoreCase = true) ||
                label.contains("playing", ignoreCase = true) ||
                label.contains("mini", ignoreCase = true)
        }
        return named ?: list.minByOrNull { it.minWidth * it.minHeight.coerceAtLeast(1) }
    }

    fun infoFor(id: Int): AppWidgetProviderInfo? {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        return runCatching { mgr.getAppWidgetInfo(id) }.getOrNull()
            ?.takeIf { it.provider.packageName.contains("spotify", ignoreCase = true) }
    }

    fun allocate(): Int = host.allocateAppWidgetId()

    fun delete(id: Int) {
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            runCatching { host.deleteAppWidgetId(id) }
        }
    }

    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo): Boolean {
        return runCatching { mgr.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
    }

    fun bindIntent(id: Int, info: AppWidgetProviderInfo): Intent {
        return Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        }
    }

    fun createView(context: Context, id: Int): AppWidgetHostView? {
        val info = infoFor(id) ?: return null
        val view = host.createView(context, id, info)
        view.setAppWidget(id, info)
        applySize(context, view, id, info)
        return view
    }

    private fun applySize(
        context: Context,
        view: AppWidgetHostView,
        id: Int,
        info: AppWidgetProviderInfo,
    ) {
        val metrics = context.resources.displayMetrics
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, metrics)
        val widthPx = (metrics.widthPixels - pad * 2).roundToInt().coerceAtLeast(1)
        val floor = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 148f, metrics).roundToInt()
        val ceil = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 220f, metrics).roundToInt()
        val heightPx = info.minHeight.coerceIn(floor, ceil)
        view.layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
        val widthDp = (widthPx / metrics.density).roundToInt()
        val heightDp = (heightPx / metrics.density).roundToInt()
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        }
        runCatching { mgr.updateAppWidgetOptions(id, opts) }
        @Suppress("DEPRECATION")
        view.updateAppWidgetSize(opts, widthDp, heightDp, widthDp, heightDp)
    }

    companion object {
        const val HOST_ID = 0xF0110
    }
}
