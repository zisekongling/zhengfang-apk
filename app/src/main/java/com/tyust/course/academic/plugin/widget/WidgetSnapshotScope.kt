package com.tyust.course.academic.plugin.widget

import android.content.Context
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import com.tyust.course.academic.plugin.*
import com.tyust.course.manager.UserManager
import org.json.JSONObject

/** Internal ownership, independent of the launcher's optional account text. */
internal data class WidgetSnapshotScope(val identity: String, val kind: String) {
    companion object {
        fun current(app: Context, pluginId: String): WidgetSnapshotScope? {
            val pkg = runCatching { AcademicProviderRegistry.packages().active(pluginId) }.getOrNull() ?: return null
            val server = pkg.manifest.json.optJSONArray("servers")?.takeIf { it.length() == 1 }?.optJSONObject(0)
            val user = UserManager.getInstance()
            val session = if (server != null) AcademicSession(
                AcademicSessionKey("service:$pluginId:${server.getString("id")}", PluginServiceAccounts(app).selected(pluginId, server.getString("id"))),
                server.getString("origin"),
            ) else PluginLegacyData.session(app, pkg, user.currentSchool, user.currentAccountStorageKey)
            return try { forSession(app, pkg, session) } finally { session.retire() }
        }

        fun forSession(app: Context, pkg: PluginPackage, session: AcademicSession): WidgetSnapshotScope {
            val kind = when {
                session.key.schoolId.startsWith("service:${pkg.manifest.id}:") -> "service"
                session.key.schoolId == "plugin:${pkg.manifest.id}" && session.key.accountKey == "default" -> "plugin"
                else -> "academic"
            }
            val data = JSONObject().put("kind", kind).put("plugin", pkg.manifest.id).put("package", pkg.digest)
                .put("development", !pkg.official).put("school", session.key.schoolId)
                .put("account", session.key.accountKey).put("baseUrl", session.baseUrl)
            when (kind) {
                // SharedPreferences reads do not acquire the service-account mutation lock.
                "service" -> data.put("generation", app.getSharedPreferences("plugin-service-accounts", Context.MODE_PRIVATE)
                    .getLong("generation:${pkg.manifest.id}", 0))
                "academic" -> {
                    val user = UserManager.getInstance()
                    val token = user.sessionState.token
                    data.put("accountStorageKey", token.accountStorageKey).put("generation", token.generation)
                        .put("configuration", user.currentSchool?.toJson() ?: JSONObject())
                }
            }
            return WidgetSnapshotScope(PluginJson.sha256(PluginJson.canonical(data).toByteArray()), kind)
        }
    }
}
