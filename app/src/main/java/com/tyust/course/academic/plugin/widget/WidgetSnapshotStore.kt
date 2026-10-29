package com.tyust.course.academic.plugin.widget

import android.content.Context
import com.tyust.course.academic.plugin.PluginJson
import com.tyust.course.academic.plugin.PluginStorageScope
import com.tyust.course.academic.plugin.PluginWidgetContract
import com.tyust.course.academic.plugin.widgetInvalid
import org.json.JSONObject
import java.io.File

/**
 * Snapshots published by plugins for one widget declaration.
 *
 * Files live under the plugin's own storage scope (`filesDir/plugin-widgets/<scope hash>`), so deleting
 * a plugin can remove every byte it ever published with a single directory delete.
 */
object WidgetSnapshotStore {
    private const val ROOT = "plugin-widgets"

    data class Snapshot(
        val pluginId: String,
        val widgetId: String,
        val viewKey: String,
        val updatedAt: Long,
        val root: JSONObject,
    ) {
        val payload: JSONObject get() = root.optJSONObject("snapshot") ?: JSONObject()
    }

    fun save(context: Context, pluginId: String, widgetId: String, viewKey: String,
             payload: JSONObject, now: Long = System.currentTimeMillis()): Snapshot {
        val root = JSONObject()
            .put("schema", 1)
            .put("pluginId", pluginId)
            .put("widgetId", widgetId)
            .put("viewKey", viewKey)
            .put("updatedAt", now)
            .put("snapshot", payload)
        val bytes = root.toString().toByteArray()
        if (bytes.size > PluginWidgetContract.MAX_SNAPSHOT_BYTES) widgetInvalid("小组件快照超过 64 KiB")
        if (totalBytes(context, pluginId) + bytes.size > PluginWidgetContract.MAX_PLUGIN_BYTES)
            widgetInvalid("插件快照总量超过 256 KiB")
        write(file(context, pluginId, widgetId), bytes)
        return Snapshot(pluginId, widgetId, viewKey, now, root)
    }

    fun read(context: Context, pluginId: String, widgetId: String): Snapshot? {
        val file = file(context, pluginId, widgetId)
        if (!file.exists()) return null
        return runCatching {
            val root = PluginJson.parse(file.readText())
            if (root.optJSONObject("snapshot") == null) return null
            Snapshot(root.optString("pluginId", pluginId), root.optString("widgetId", widgetId),
                root.optString("viewKey"), root.optLong("updatedAt"), root)
        }.getOrNull()
    }

    fun clearPlugin(context: Context, pluginId: String) {
        dir(context, pluginId).deleteRecursively()
    }

    fun clearWidget(context: Context, pluginId: String, widgetId: String) {
        file(context, pluginId, widgetId).delete()
    }

    fun totalBytes(context: Context, pluginId: String): Long =
        dir(context, pluginId).listFiles()?.sumOf { it.length() } ?: 0L

    private fun dir(context: Context, pluginId: String) =
        File(File(context.filesDir, ROOT), PluginStorageScope.hash("widget\u0000$pluginId"))

    private fun file(context: Context, pluginId: String, widgetId: String) =
        File(dir(context, pluginId), PluginStorageScope.hash(widgetId) + ".json")

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
