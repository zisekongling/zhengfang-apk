package com.tyust.course.academic.plugin

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class BrowserComponentHostTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val profile = "plugin_" + "b".repeat(64)

    private fun installBrowser(firstInstallTime: Long) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = BrowserComponentHost.PACKAGE; versionName = "157.1"; this.firstInstallTime = firstInstallTime
        })
    }

    @Test fun missingComponentBlocksBrowserEntryPointsWithUnsupported() {
        assertEquals(BrowserComponentHost.State.MISSING, BrowserComponentHost.status(app).state)
        val error = assertThrows(PluginException::class.java) { PluginEmbeddedBrowser.requireSupport(app) }
        assertEquals(PluginErrorCode.UNSUPPORTED, error.code)
        assertTrue(error.message!!.contains("内置浏览器"))
    }

    @Test fun nativeStoreStaysAuthoritativeUntilThisInstallTookOver() {
        assertFalse(PluginEmbeddedBrowser.geckoAuthority(app, profile))
        installBrowser(1000)
        assertFalse("Installing alone must not move the account", PluginEmbeddedBrowser.geckoAuthority(app, profile))
        app.getSharedPreferences("plugin-embedded-migration-v2", Context.MODE_PRIVATE).edit()
            .putString("authority:$profile", BrowserComponentHost.installIdentity(app)).commit()
        assertTrue(PluginEmbeddedBrowser.geckoAuthority(app, profile))
        // A reinstall starts with an empty Gecko profile: fall back to the native store.
        shadowOf(app.packageManager).removePackage(BrowserComponentHost.PACKAGE)
        assertFalse(PluginEmbeddedBrowser.geckoAuthority(app, profile))
        installBrowser(2000)
        assertFalse(PluginEmbeddedBrowser.geckoAuthority(app, profile))
    }

    @Test fun downloadCandidatesUseOnlyAllowListedHosts() {
        val urls = BrowserComponentHost.candidates("arm64-v8a")
        assertTrue(urls.all(com.tyust.course.update.UpdateManifestVerifier::validUrl))
        assertTrue(urls.all { it.endsWith("/browser-arm64-v8a-release.apk") })
    }
}
