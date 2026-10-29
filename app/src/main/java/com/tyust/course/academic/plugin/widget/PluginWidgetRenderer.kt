package com.tyust.course.academic.plugin.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.app.PendingIntent
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.tyust.course.R
import com.tyust.course.academic.plugin.NativePluginActivity
import com.tyust.course.academic.plugin.PluginCenterActivity
import com.tyust.course.academic.plugin.PluginJson
import com.tyust.course.academic.plugin.PluginPackage
import com.tyust.course.academic.plugin.PluginWidgetContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps a published snapshot onto RemoteViews. Widgets only ever read stored JSON here, so no plugin
 * code runs inside the launcher process.
 */
object PluginWidgetRenderer {
    private class Palette(val primary: Int, val secondary: Int, val accent: Int)

    fun responsive(
        context: Context,
        pkg: PluginPackage?,
        binding: PluginWidgetBindings.Binding,
        snapshot: WidgetSnapshotStore.Snapshot?,
        staleAfterSeconds: Long,
        options: Bundle,
    ): RemoteViews {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = sizes(options)
            if (sizes.isNotEmpty()) return multiSize(context, pkg, binding, snapshot, staleAfterSeconds, sizes)
        }
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        return views(context, pkg, binding, snapshot, staleAfterSeconds, width, height)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun sizes(options: Bundle): List<SizeF> =
        options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            ?.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
            ?.distinct()?.take(16).orEmpty()

    @RequiresApi(Build.VERSION_CODES.S)
    private fun multiSize(
        context: Context,
        pkg: PluginPackage?,
        binding: PluginWidgetBindings.Binding,
        snapshot: WidgetSnapshotStore.Snapshot?,
        staleAfterSeconds: Long,
        sizes: List<SizeF>,
    ): RemoteViews = RemoteViews(sizes.associateWith { size ->
        views(context, pkg, binding, snapshot, staleAfterSeconds, size.width.toInt(), size.height.toInt())
    })

    fun views(
        context: Context,
        pkg: PluginPackage?,
        binding: PluginWidgetBindings.Binding,
        snapshot: WidgetSnapshotStore.Snapshot?,
        staleAfterSeconds: Long,
        widthDp: Int,
        heightDp: Int,
    ): RemoteViews {
        val layout = when (binding.template) {
            "lines" -> R.layout.plugin_widget_lines
            "grid" -> R.layout.plugin_widget_grid
            "countdown" -> R.layout.plugin_widget_countdown
            else -> R.layout.plugin_widget_text
        }
        val family = binding.family()
        val dark = when (binding.theme) {
            "dark" -> true
            "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val view = RemoteViews(context.packageName, layout)
        val palette = palette(context, dark)
        val padding = when (family) {
            PluginWidgetContract.Family.Large -> 16
            PluginWidgetContract.Family.Medium -> 12
            else -> if (widthDp >= 220) 12 else 8
        }.let { dp(context, it) }
        view.setViewPadding(R.id.plugin_widget_surface, padding, padding, padding, padding)
        view.setInt(R.id.plugin_widget_surface, "setBackgroundResource", R.drawable.plugin_widget_surface)
        view.setTextColor(R.id.plugin_widget_title, palette.primary)
        view.setTextColor(R.id.plugin_widget_subtitle, palette.secondary)
        view.setTextColor(R.id.plugin_widget_footer, palette.secondary)
        view.setTextColor(R.id.plugin_widget_empty, palette.secondary)
        view.setTextColor(R.id.plugin_widget_action, palette.accent)
        view.setTextColor(R.id.plugin_widget_badge, palette.accent)
        view.setTextColor(R.id.plugin_widget_main, palette.primary)
        view.setTextColor(R.id.plugin_widget_timer_label, palette.secondary)
        view.setTextColor(R.id.plugin_widget_chronometer, palette.accent)
        applyTextScale(view, family, widthDp)

        if (pkg == null) {
            empty(view, "插件已移除", "轻触打开插件中心")
            view.setOnClickPendingIntent(R.id.plugin_widget_surface,
                PendingIntent.getActivity(context, binding.appWidgetId * 10 + 1,
                    Intent(context, PluginCenterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return view
        }
        if (snapshot == null) {
            empty(view, "等待插件数据", "轻触刷新")
            view.setOnClickPendingIntent(R.id.plugin_widget_surface, broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_REFRESH))
            return view
        }

        val payload = snapshot.payload
        val header = payload.optJSONObject("header") ?: JSONObject()
        val rows = rotate(PluginJson.objects(payload.optJSONArray("rows") ?: JSONArray()), binding.offset)
        val title = header.optString("title").ifBlank { binding.viewKey.ifBlank { "小组件" } }
        val subtitle = header.optString("subtitle")
        val footer = payload.optString("footer")
        val stale = staleAfterSeconds > 0 && System.currentTimeMillis() - snapshot.updatedAt > staleAfterSeconds * 1_000L

        view.setTextViewText(R.id.plugin_widget_title, title)
        when (binding.template) {
            "lines" -> renderLines(view, rows, family, widthDp, heightDp)
            "grid" -> renderGrid(view, rows, family, widthDp, heightDp)
            "countdown" -> renderCountdown(view, payload, rows, subtitle)
            else -> renderText(view, header, rows, subtitle)
        }
        if (binding.template != "text" && binding.template != "countdown") {
            view.setTextViewText(R.id.plugin_widget_subtitle, subtitle)
            visible(view, R.id.plugin_widget_subtitle, subtitle.isNotBlank())
        }
        val footerText = listOf(footer, header.optString("date"), if (stale) "数据陈旧 · 轻触刷新" else "")
            .filter(String::isNotBlank).joinToString(" · ")
        view.setTextViewText(R.id.plugin_widget_footer, footerText)
        visible(view, R.id.plugin_widget_footer, footerText.isNotBlank())

        val actions = PluginJson.objects(payload.optJSONArray("actions") ?: JSONArray())
        val action = actions.firstOrNull()
        val actionLabel = action?.optString("label").orEmpty().ifBlank { "刷新" }
        view.setTextViewText(R.id.plugin_widget_action, if (actions.isEmpty()) "⟳" else actionLabel)
        visible(view, R.id.plugin_widget_action, family != PluginWidgetContract.Family.Small || widthDp >= 180)
        view.setOnClickPendingIntent(R.id.plugin_widget_action, actionIntent(context, binding, pkg, action))

        val pages = PluginJson.objects(pkg.manifest.contributes.optJSONArray("pages") ?: JSONArray())
            .map { it.optString("id") }.filter(String::isNotBlank)
        val openPage = actions.firstOrNull { it.optString("kind") == "openPage" }?.optString("pageId")
            ?: pages.firstOrNull()
        view.setOnClickPendingIntent(R.id.plugin_widget_surface, if (openPage != null) activity(context, binding, pkg, openPage)
            else broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_REFRESH))
        return view
    }

    private fun renderText(view: RemoteViews, header: JSONObject, rows: List<JSONObject>, subtitle: String) {
        val row = rows.firstOrNull()
        val main = row?.optString("title").orEmpty().ifBlank { subtitle }
        val line = row?.optString("subtitle").orEmpty()
        view.setTextViewText(R.id.plugin_widget_main, main)
        view.setTextViewText(R.id.plugin_widget_subtitle, line.ifBlank { subtitle })
        visible(view, R.id.plugin_widget_main, main.isNotBlank())
        visible(view, R.id.plugin_widget_subtitle, line.ifBlank { subtitle }.isNotBlank())
        val badge = row?.optString("badge").orEmpty()
        view.setTextViewText(R.id.plugin_widget_badge, badge)
        visible(view, R.id.plugin_widget_badge, badge.isNotBlank())
        if (rows.size > 1) {
            val second = rows[1].optString("title")
            view.setTextViewText(R.id.plugin_widget_subtitle, listOf(line, second).filter(String::isNotBlank).joinToString(" · "))
        }
    }

    private fun renderLines(view: RemoteViews, rows: List<JSONObject>, family: PluginWidgetContract.Family?, widthDp: Int, heightDp: Int) {
        val capacity = when (family) {
            PluginWidgetContract.Family.Large -> if (heightDp >= 160) 5 else 4
            PluginWidgetContract.Family.Medium -> 4
            else -> if (widthDp >= 220) 2 else 1
        }
        val ids = intArrayOf(R.id.plugin_widget_row_1, R.id.plugin_widget_row_2, R.id.plugin_widget_row_3, R.id.plugin_widget_row_4)
        for (index in ids.indices) {
            val id = ids[index]
            val row = rows.getOrNull(index)
            val text = row?.let { line(it) }.orEmpty()
            view.setTextViewText(id, text)
            visible(view, id, index < capacity && text.isNotBlank())
        }
        if (rows.size > capacity) {
            val more = "还有 ${rows.size - capacity} 项，轻触查看"
            view.setTextViewText(R.id.plugin_widget_footer, more)
            visible(view, R.id.plugin_widget_footer, true)
        }
    }

    private fun line(row: JSONObject): String {
        val title = row.optString("title")
        val time = row.optString("time")
        val subtitle = row.optString("subtitle")
        val badge = row.optString("badge")
        val head = listOf(title, time).filter(String::isNotBlank).joinToString("  ")
        val tail = listOf(subtitle, badge).filter(String::isNotBlank).joinToString(" · ")
        return if (tail.isBlank()) head else "$head\n$tail"
    }

    private fun renderGrid(view: RemoteViews, rows: List<JSONObject>, family: PluginWidgetContract.Family?, widthDp: Int, heightDp: Int) {
        val cells = when (family) {
            PluginWidgetContract.Family.Large -> if (heightDp >= 160) 6 else 4
            PluginWidgetContract.Family.Medium -> 4
            else -> if (widthDp >= 300) 3 else 2
        }
        val ids = intArrayOf(R.id.plugin_widget_cell_1, R.id.plugin_widget_cell_2, R.id.plugin_widget_cell_3,
            R.id.plugin_widget_cell_4, R.id.plugin_widget_cell_5, R.id.plugin_widget_cell_6)
        for (index in ids.indices) {
            val row = rows.getOrNull(index)
            val text = row?.let { cell(it) }.orEmpty()
            view.setTextViewText(ids[index], text)
            visible(view, ids[index], index < cells && text.isNotBlank())
        }
    }

    private fun cell(row: JSONObject): String {
        val title = row.optString("title")
        val subtitle = row.optString("subtitle")
        val time = row.optString("time")
        return listOf(title, subtitle, time).filter(String::isNotBlank).joinToString("\n")
    }

    private fun renderCountdown(view: RemoteViews, payload: JSONObject, rows: List<JSONObject>, subtitle: String) {
        val timer = payload.optJSONObject("timer")
        if (timer == null) {
            val row = rows.firstOrNull()
            view.setTextViewText(R.id.plugin_widget_timer_label, row?.optString("title").orEmpty().ifBlank { subtitle })
            visible(view, R.id.plugin_widget_chronometer, false)
            visible(view, R.id.plugin_widget_timer_label, true)
            return
        }
        val now = System.currentTimeMillis()
        val endAt = timer.optLong("endAt")
        val remaining = endAt - now
        val label = timer.optString("label").ifBlank { if (remaining > 0) "剩余时间" else "已结束" }
        view.setTextViewText(R.id.plugin_widget_timer_label, label)
        visible(view, R.id.plugin_widget_timer_label, true)
        val countdown = timer.optBoolean("countdown", true) && remaining > 0
        visible(view, R.id.plugin_widget_chronometer, countdown)
        if (countdown) {
            val base = SystemClock.elapsedRealtime() + remaining
            view.setChronometer(R.id.plugin_widget_chronometer, base, null, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) view.setChronometerCountDown(R.id.plugin_widget_chronometer, true)
        }
    }

    private fun empty(view: RemoteViews, text: String, hint: String) {
        view.setTextViewText(R.id.plugin_widget_empty, text)
        view.setTextViewText(R.id.plugin_widget_title, "")
        visible(view, R.id.plugin_widget_title, false)
        visible(view, R.id.plugin_widget_subtitle, false)
        visible(view, R.id.plugin_widget_main, false)
        visible(view, R.id.plugin_widget_badge, false)
        visible(view, R.id.plugin_widget_footer, false)
        visible(view, R.id.plugin_widget_action, false)
        visible(view, R.id.plugin_widget_empty, true)
        if (hint.isNotBlank()) view.setContentDescription(R.id.plugin_widget_surface, hint)
    }

    private fun applyTextScale(view: RemoteViews, family: PluginWidgetContract.Family?, widthDp: Int) {
        val (title, main) = when (family) {
            PluginWidgetContract.Family.Large -> 15 to 20
            PluginWidgetContract.Family.Medium -> 14 to 17
            else -> if (widthDp >= 220) 14 to 17 else 12 to 14
        }
        view.setTextViewTextSize(R.id.plugin_widget_title, TypedValue.COMPLEX_UNIT_SP, title.toFloat())
        view.setTextViewTextSize(R.id.plugin_widget_main, TypedValue.COMPLEX_UNIT_SP, main.toFloat())
        view.setTextViewTextSize(R.id.plugin_widget_chronometer, TypedValue.COMPLEX_UNIT_SP, (main + 8).toFloat())
    }

    private fun palette(context: Context, dark: Boolean): Palette {
        val themed = themed(context, dark)
        return Palette(
            themed.getColor(R.color.text_primary),
            themed.getColor(R.color.text_secondary),
            themed.getColor(R.color.primary),
        )
    }

    private fun themed(context: Context, dark: Boolean): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return context.createConfigurationContext(configuration)
    }

    private fun activity(context: Context, binding: PluginWidgetBindings.Binding, pkg: PluginPackage, pageId: String): PendingIntent =
        PendingIntent.getActivity(context, binding.appWidgetId * 10 + 2,
            Intent(context, NativePluginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("pluginId", pkg.manifest.id)
                .putExtra("pageId", pageId)
                .putExtra("pageParams", "{}"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun actionIntent(
        context: Context,
        binding: PluginWidgetBindings.Binding,
        pkg: PluginPackage,
        action: JSONObject?,
    ): PendingIntent {
        if (action == null) return broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_REFRESH)
        return when (action.optString("kind")) {
            "openPage" -> activity(context, binding, pkg, action.optString("pageId"))
            "cycle" -> broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_CYCLE)
            else -> broadcast(context, binding.appWidgetId, PluginWidgets.ACTION_REFRESH)
        }
    }

    private fun broadcast(context: Context, appWidgetId: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(context, appWidgetId * 10 + 3,
            Intent(context, PluginWidgetSmallProvider::class.java)
                .setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun visible(view: RemoteViews, id: Int, show: Boolean) =
        view.setViewVisibility(id, if (show) View.VISIBLE else View.GONE)

    /** "Change one" rotates the visible rows locally; the launcher never re-runs plugin code. */
    private fun rotate(rows: List<JSONObject>, offset: Int): List<JSONObject> {
        if (offset == 0 || rows.size < 2) return rows
        val shift = offset.mod(rows.size)
        return rows.drop(shift) + rows.take(shift)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    internal fun componentName(context: Context, family: PluginWidgetContract.Family): ComponentName = when (family) {
        PluginWidgetContract.Family.Medium -> ComponentName(context, PluginWidgetMediumProvider::class.java)
        PluginWidgetContract.Family.Large -> ComponentName(context, PluginWidgetLargeProvider::class.java)
        else -> ComponentName(context, PluginWidgetSmallProvider::class.java)
    }
}
