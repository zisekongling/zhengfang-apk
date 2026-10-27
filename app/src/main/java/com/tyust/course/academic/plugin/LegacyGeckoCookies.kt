package com.tyust.course.academic.plugin

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Reads the cookie table of a Gecko profile that the App itself ran before the browser became a
 * separate APK. Gecko is no longer in this process, so the file is quiescent. Returns (url, Set-Cookie).
 */
internal object LegacyGeckoCookies {
    fun read(file: File, now: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        if (!file.isFile) return emptyList()
        return runCatching {
            // Read-write lets SQLite fold a leftover WAL in; the directory is deleted afterwards.
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // Gecko 157 schema. Partitioned (third-party) cookies carry originAttributes; never
                // import those as first-party cookies.
                db.rawQuery("SELECT name, value, host, path, expiry, isSecure, isHttpOnly FROM moz_cookies WHERE originAttributes = ''", null).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            row(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5) != 0, c.getInt(6) != 0, now)?.let(::add)
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Gecko stores expiry in seconds (older profiles) or milliseconds (Gecko 136+). */
    internal fun row(name: String?, value: String?, host: String?, path: String?, expiry: Long, secure: Boolean, httpOnly: Boolean, now: Long): Pair<String, String>? {
        if (name.isNullOrEmpty() || value == null || host.isNullOrBlank()) return null
        val expiresAt = if (expiry > 100_000_000_000L) expiry else expiry * 1000
        if (expiresAt <= now) return null
        val domain = host.startsWith('.')
        val bare = host.removePrefix(".")
        if (!bare.matches(Regex("[A-Za-z0-9.-]{1,253}"))) return null
        val cookiePath = path?.takeIf { it.startsWith('/') } ?: "/"
        val header = buildString {
            append(name).append('=').append(value)
            if (domain) append("; Domain=").append(bare)
            append("; Path=").append(cookiePath)
            append("; Max-Age=").append(((expiresAt - now) / 1000).coerceAtLeast(1))
            if (secure) append("; Secure")
            if (httpOnly) append("; HttpOnly")
        }
        return "https://$bare$cookiePath" to header
    }
}
