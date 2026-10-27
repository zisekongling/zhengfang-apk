package com.tyust.course.academic.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** Account profiles must not outlive the App that owns the accounts. */
class BrowserHostRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the system broadcasts only: an explicit intent from another app has no such action.
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED && intent.action != Intent.ACTION_PACKAGE_DATA_CLEARED) return
        if (intent.data?.schemeSpecificPart != hostPackage(context)) return
        File(context.noBackupFilesDir, "embedded-browser").deleteRecursively()
    }
    companion object {
        /** Each browser variant serves exactly one App variant. */
        @Suppress("UNUSED_PARAMETER") fun hostPackage(context: Context): String = com.tyust.course.browser.BuildConfig.HOST_PACKAGE
    }
}
