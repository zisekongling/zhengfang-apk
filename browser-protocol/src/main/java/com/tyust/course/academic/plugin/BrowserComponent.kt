package com.tyust.course.academic.plugin

import android.content.Context
import android.content.pm.PackageManager
import java.net.URI

/** Contract shared by the App and the separately installed GeckoView APK. */
object BrowserComponent {
    /** Bumped on any incompatible change to actions or their JSON. Both sides reject a mismatch. */
    const val PROTOCOL = 1
    const val SERVICE = "com.tyust.course.academic.plugin.PluginEmbeddedBrowserService"
    const val ACTIVITY = "com.tyust.course.academic.plugin.PluginEmbeddedBrowserActivity"
    /** The four WebExtension files whose bytes are part of the SDK contract lock. */
    val EXTENSION_FILES = listOf("manifest.json", "background.js", "api.js", "environment.js")
    const val EXTENSION_ROOT = "academic-plugin/gecko/"
    val PROFILE = Regex("plugin_[a-f0-9]{64}")

    /** Same rule as PluginWebGate.owns: exact scheme and authority, no user info. */
    fun owns(origin: String, url: String): Boolean = runCatching {
        val uri = URI(url); uri.userInfo == null && "${uri.scheme}://${uri.rawAuthority}" == origin
    }.getOrDefault(false)

    /** Account cookies cross this boundary: accept only [expected], signed exactly like this APK. */
    fun trusted(context: Context, uid: Int, expected: String): Boolean {
        val pm = context.packageManager
        return pm.getPackagesForUid(uid)?.contains(expected) == true && sameSigner(context, expected)
    }

    fun sameSigner(context: Context, other: String): Boolean = runCatching {
        context.packageManager.checkSignatures(context.packageName, other) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun extensionDigests(context: Context): Map<String, String> = EXTENSION_FILES.associateWith { name ->
        sha256(context.assets.open(EXTENSION_ROOT + name).use { it.readBytes() })
    }
}
