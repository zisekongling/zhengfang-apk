package com.tyust.course.academic.plugin

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class LegacyGeckoCookiesTest {
    private val now = 1_800_000_000_000L

    private fun parse(row: Pair<String, String>): Cookie = Cookie.parse(row.first.toHttpUrl(), row.second)!!

    @Test fun domainCookieKeepsDomainPathAndFlags() {
        val row = LegacyGeckoCookies.row("UID", "42", ".chaoxing.com", "/mooc", now / 1000 + 3600, true, true, now)!!
        assertEquals("https://chaoxing.com/mooc", row.first)
        val cookie = parse(row)
        assertEquals("chaoxing.com", cookie.domain)
        assertFalse(cookie.hostOnly)
        assertEquals("/mooc", cookie.path)
        assertTrue(cookie.secure && cookie.httpOnly)
        assertTrue(cookie.matches("https://mooc1.chaoxing.com/mooc/x".toHttpUrl()))
    }

    @Test fun hostOnlyCookieStaysOnItsHost() {
        val cookie = parse(LegacyGeckoCookies.row("JSESSIONID", "a", "passport2.chaoxing.com", "/", now / 1000 + 60, false, false, now)!!)
        assertTrue(cookie.hostOnly)
        assertFalse(cookie.matches("https://i.chaoxing.com/".toHttpUrl()))
    }

    @Test fun acceptsSecondsAndMillisecondsExpiry() {
        val seconds = LegacyGeckoCookies.row("a", "1", "x.test", "/", now / 1000 + 7200, false, false, now)!!
        val millis = LegacyGeckoCookies.row("a", "1", "x.test", "/", now + 7_200_000, false, false, now)!!
        assertEquals(seconds, millis)
        assertTrue(seconds.second.contains("Max-Age=7200"))
    }

    @Test fun readsTheGecko157TableAndSkipsPartitionedCookies() {
        val file = java.io.File.createTempFile("cookies", ".sqlite").apply { delete() }
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                // Exact CREATE statement found in GeckoView 157's libxul.
                db.execSQL("CREATE TABLE moz_cookies (id INTEGER PRIMARY KEY, originAttributes TEXT NOT NULL DEFAULT '', name TEXT, value TEXT, host TEXT, path TEXT, expiry INTEGER, lastAccessed INTEGER, creationTime INTEGER, isSecure INTEGER, isHttpOnly INTEGER, inBrowserElement INTEGER DEFAULT 0, sameSite INTEGER DEFAULT 0, rawSameSite INTEGER DEFAULT 0, CONSTRAINT moz_uniqueid UNIQUE (name, host, path, originAttributes))")
                val future = System.currentTimeMillis() + 3_600_000
                db.execSQL("INSERT INTO moz_cookies (originAttributes, name, value, host, path, expiry, isSecure, isHttpOnly) VALUES ('', 'UID', '1', '.chaoxing.com', '/', $future, 1, 1)")
                db.execSQL("INSERT INTO moz_cookies (originAttributes, name, value, host, path, expiry, isSecure, isHttpOnly) VALUES ('^partitionKey=(https,tracker.test)', 'T', '2', '.chaoxing.com', '/', $future, 1, 0)")
            }
            val rows = LegacyGeckoCookies.read(file)
            assertEquals(listOf("UID"), rows.map { it.second.substringBefore('=') })
        } finally { file.delete(); java.io.File(file.path + "-journal").delete() }
    }

    @Test fun missingOrForeignFileYieldsNothing() {
        assertTrue(LegacyGeckoCookies.read(java.io.File("does-not-exist.sqlite")).isEmpty())
        val junk = java.io.File.createTempFile("junk", ".sqlite").apply { writeText("not sqlite") }
        try { assertTrue(LegacyGeckoCookies.read(junk).isEmpty()) } finally { junk.delete() }
    }

    @Test fun dropsExpiredAndMalformedRows() {
        assertNull(LegacyGeckoCookies.row("a", "1", "x.test", "/", now / 1000 - 1, false, false, now))
        assertNull(LegacyGeckoCookies.row("", "1", "x.test", "/", now / 1000 + 60, false, false, now))
        assertNull(LegacyGeckoCookies.row("a", "1", "x.test/evil", "/", now / 1000 + 60, false, false, now))
        assertNull(LegacyGeckoCookies.row("a", "1", null, "/", now / 1000 + 60, false, false, now))
        assertEquals("https://x.test/", LegacyGeckoCookies.row("a", "1", "x.test", "relative", now / 1000 + 60, false, false, now)!!.first)
    }
}
