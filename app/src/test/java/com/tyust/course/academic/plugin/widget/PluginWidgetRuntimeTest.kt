package com.tyust.course.academic.plugin.widget

import android.app.Application
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.tyust.course.R
import com.tyust.course.academic.plugin.*
import com.tyust.course.manager.UserManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppWidgetManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Implements(AppWidgetManager::class)
class PinWidgetManager : ShadowAppWidgetManager() {
    var callback: PendingIntent? = null
    @Implementation override fun requestPinAppWidget(provider: ComponentName, extras: Bundle?, successCallback: PendingIntent?): Boolean {
        callback = successCallback
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, shadows = [PinWidgetManager::class])
class PluginWidgetRuntimeTest {
    private lateinit var app: Application
    private lateinit var manager: AppWidgetManager
    private lateinit var jobs: JobScheduler
    private lateinit var alarms: AlarmManager
    private lateinit var pkg: PluginPackage

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        UserManager.getInstance().init(app)
        UserManager.getInstance().currentSchool = null
        AcademicProviderRegistry.initialize(app)
        PluginPages.initialize(app)
        manager = AppWidgetManager.getInstance(app)
        jobs = app.getSystemService(JobScheduler::class.java)
        alarms = app.getSystemService(AlarmManager::class.java)
        pkg = install()
    }

    private fun install(): PluginPackage = runBlocking {
        val manifest = JSONObject("""{"id":"test.widget-runtime","name":"Synthetic widget","version":"1.0.0","apiVersion":3,
          "kind":"widget","entry":"index.js","minAppVersionCode":130,"permissions":["widget","runtime"],"capabilities":["data.query"],
          "requires":[{"name":"widget.render","version":1},{"name":"widget.publish","version":1},{"name":"data.query","version":1}],
          "network":[],"files":{"index.js":"${PluginJson.sha256(byteArrayOf())}"},"contributes":{"pages":[],"entries":[],
          "dataProviders":[{"id":"test.widget-runtime.data","title":"Synthetic"}],"widgets":[
          {"id":"push","title":"Push","sizes":["2x2"],"template":"countdown","defaultView":"a","views":[{"key":"a","title":"A"},{"key":"b","title":"B"}],"refresh":{"mode":"push"}},
          {"id":"pull","title":"Pull","sizes":["2x2"],"template":"text","views":[{"key":"a","title":"A"}],"options":[{"id":"count","title":"Count","type":"number","default":2,"scope":"instance"}],"refresh":{"mode":"pull","providerId":"test.widget-runtime.data"}}]}}""")
        val archive = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            listOf("manifest.json" to manifest.toString().toByteArray(), "index.js" to byteArrayOf()).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        } }.toByteArray()
        AcademicProviderRegistry.packages().install(archive, true).also { AcademicProviderRegistry.reload() }
    }

    private fun binding(widget: String = "push", view: String = "a", template: String = "countdown"): PluginWidgetBindings.Binding {
        val id = shadowOf(manager).createWidget(PluginWidgetMediumProvider::class.java, R.layout.plugin_widget_text)
        shadowOf(manager).putWidgetInfo(id, AppWidgetProviderInfo().apply {
            provider = ComponentName(app, PluginWidgetMediumProvider::class.java)
        })
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
        })
        return PluginWidgetBindings.Binding(id, pkg.manifest.id, widget, view, template, "light", "", JSONObject(), "2x2", 1L)
            .also { PluginWidgetBindings.save(app, it) }
    }

    private fun payload(title: String, end: Long? = null) = JSONObject().put("schema", 1)
        .put("header", JSONObject().put("title", title))
        .put("rows", JSONArray().put(JSONObject().put("id", "r").put("title", title))).apply {
            end?.let { put("timer", JSONObject().put("startAt", System.currentTimeMillis() - 1000).put("endAt", it).put("countdown", true)) }
        }
    private fun publish(view: String, title: String, end: Long? = null) = PluginWidgets.publish(app, pkg.manifest,
        JSONObject().put("widgetId", "push").put("viewKey", view).put("snapshot", payload(title, end)))

    @Test fun periodicAndImmediateJobsCarryPluginIdentity() {
        PluginWidgetPullService.schedule(app, pkg.manifest.id)
        PluginWidgetPullService.requestImmediate(app, pkg.manifest.id)
        assertEquals(2, jobs.allPendingJobs.size)
        jobs.allPendingJobs.forEach { assertEquals(pkg.manifest.id, it.extras.getString("pluginId")) }
    }

    @Test fun existingBrokenPeriodicJobIsRepaired() {
        PluginWidgetPullService.schedule(app, pkg.manifest.id)
        val old = jobs.allPendingJobs.single()
        jobs.schedule(android.app.job.JobInfo.Builder(old.id, old.service).setPeriodic(900_000).setPersisted(true).build())
        PluginWidgetPullService.schedule(app, pkg.manifest.id)
        assertEquals(pkg.manifest.id, jobs.allPendingJobs.single().extras.getString("pluginId"))
    }

    @Test fun pinRequestsSupplyDistinctMutableOneShotConfigurationCallbacks() {
        shadowOf(manager).setRequestPinAppWidgetSupported(true)
        PluginWidgets.requestPin(app, PluginWidgetContract.Family.Medium)
        val shadow = org.robolectric.shadow.api.Shadow.extract<PinWidgetManager>(manager)
        val first = shadow.callback
        assertNotNull("Launcher success must open configuration with its assigned id", first)
        PluginWidgets.requestPin(app, PluginWidgetContract.Family.Medium)
        val second = shadow.callback!!
        assertNotEquals(first, second)
        val pending = shadowOf(second)
        assertTrue(pending.isActivity)
        assertFalse(pending.isImmutable)
        assertTrue(pending.flags and PendingIntent.FLAG_ONE_SHOT != 0)
        assertEquals(PluginWidgetConfigActivity::class.java.name, pending.savedIntent.component?.className)
    }

    @Test @Config(sdk = [24]) fun pinOnApi24UsesTheLauncherAddHintWithoutCreatingATicket() {
        PluginWidgets.requestPin(app, PluginWidgetContract.Family.Medium)
        assertNull(org.robolectric.shadow.api.Shadow.extract<PinWidgetManager>(manager).callback)
        assertTrue(app.getSharedPreferences("plugin-widget-pin-requests", 0).all.isEmpty())
    }

    @Test fun pinSuccessUsesTheLauncherAssignedIdAndConsumesItsTicketOnce() {
        val instance = binding()
        val callback = PluginWidgetPinRequests.callback(app, PluginWidgetContract.Family.Medium)
        callback.send(app, 0, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, instance.appWidgetId))
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(instance.appWidgetId, opened.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertEquals(PluginWidgetConfigActivity::class.java.name, opened.component?.className)
        assertTrue(PluginWidgetPinRequests.consume(app, opened, PluginWidgetContract.Family.Medium))
        assertFalse(PluginWidgetPinRequests.consume(app, opened, PluginWidgetContract.Family.Medium))
        assertThrows(PendingIntent.CanceledException::class.java) { callback.send() }
    }

    @Test fun pinTicketRejectsForgedOrWrongFamilyIdsWithoutConsumingTheRealRequest() {
        val instance = binding()
        val callback = PluginWidgetPinRequests.callback(app, PluginWidgetContract.Family.Small)
        val intent = Intent(shadowOf(callback).savedIntent).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, instance.appWidgetId)
        assertFalse(PluginWidgetPinRequests.consume(app, intent, PluginWidgetContract.Family.Medium))
        val smallId = shadowOf(manager).createWidget(PluginWidgetSmallProvider::class.java, R.layout.plugin_widget_text)
        shadowOf(manager).putWidgetInfo(smallId, AppWidgetProviderInfo().apply {
            provider = ComponentName(app, PluginWidgetSmallProvider::class.java)
        })
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, smallId)
        assertFalse(PluginWidgetPinRequests.consume(app, Intent(intent).setData(Uri.parse("plugin-widget-pin://forged")), PluginWidgetContract.Family.Small))
        assertTrue(PluginWidgetPinRequests.consume(app, intent, PluginWidgetContract.Family.Small))
    }

    @Test fun publishingAnotherViewDoesNotOverwriteTheFirstLauncherView() {
        val a = binding(view = "a", template = "text")
        val b = binding(view = "b", template = "text")
        publish("a", "Alpha")
        publish("b", "Beta")
        PluginWidgets.refreshAll(app)
        assertEquals("Alpha", shadowOf(manager).getViewFor(a.appWidgetId).findViewById<TextView>(R.id.plugin_widget_main).text.toString())
        assertEquals("Beta", shadowOf(manager).getViewFor(b.appWidgetId).findViewById<TextView>(R.id.plugin_widget_main).text.toString())
    }

    @Test fun refreshBroadcastEnqueuesPullForOnlyTheBoundDeclaration() {
        val pull = binding("pull", template = "text")
        val push = binding()
        jobs.cancelAll()
        PluginWidgetMediumProvider().onReceive(app, android.content.Intent(PluginWidgets.ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, push.appWidgetId))
        assertTrue(jobs.allPendingJobs.isEmpty())
        PluginWidgetMediumProvider().onReceive(app, android.content.Intent(PluginWidgets.ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pull.appWidgetId))
        assertEquals(1, jobs.allPendingJobs.size)
    }

    @Test fun pulledSnapshotsDoNotLeakBetweenInstancesWithDifferentOptions() {
        val a = binding("pull", template = "text").copy(options = JSONObject().put("count", 1))
        val b = binding("pull", template = "text").copy(options = JSONObject().put("count", 3))
        listOf(a, b).forEach { PluginWidgetBindings.save(app, it) }
        val declaration = PluginWidgetContract.declaration(pkg.manifest, "pull")!!
        listOf(a to "One", b to "Three").forEach { (instance, title) ->
            val snapshot = PluginWidgetPull.snapshotFromItems(declaration,
                JSONArray().put(JSONObject().put("id", "r").put("title", title)), System.currentTimeMillis())
            PluginWidgetPull.storeResult(app, instance, declaration, snapshot, System.currentTimeMillis()) { true }
        }
        PluginWidgets.refreshAll(app)
        assertEquals("One", shadowOf(manager).getViewFor(a.appWidgetId).findViewById<TextView>(R.id.plugin_widget_main).text.toString())
        assertEquals("Three", shadowOf(manager).getViewFor(b.appWidgetId).findViewById<TextView>(R.id.plugin_widget_main).text.toString())
    }

    @Test fun legacyPullCacheWithoutInstanceIdentityIsNotRendered() {
        val pull = binding("pull", template = "text")
        val old = legacy(pull, "Unknown instance")
        PluginWidgets.refreshId(app, pull.appWidgetId)
        assertTrue(shadowOf(manager).getViewFor(pull.appWidgetId).findViewById<TextView>(R.id.plugin_widget_empty).text.contains("等待"))
        assertFalse(old.exists())
    }

    private fun legacy(binding: PluginWidgetBindings.Binding, title: String): File {
        val dir = File(app.filesDir, "plugin-widgets/${PluginStorageScope.hash("widget\u0000${binding.pluginId}")}")
        dir.mkdirs()
        return File(dir, PluginStorageScope.hash(binding.widgetId) + ".json").also { file -> file.writeText(JSONObject()
            .put("schema", 1).put("pluginId", binding.pluginId).put("widgetId", binding.widgetId)
            .put("viewKey", binding.viewKey).put("updatedAt", 123L).put("snapshot", payload(title)).toString()) }
    }

    @Test fun legacyPushMigratesOnlyToItsRecordedView() {
        val a = binding(template = "text")
        val old = legacy(a, "Legacy A")
        assertNull(WidgetSnapshotStore.read(app, a.pluginId, a.widgetId, "b"))
        val migrated = WidgetSnapshotStore.read(app, a.pluginId, a.widgetId, "a")!!
        assertEquals("Legacy A", migrated.payload.getJSONObject("header").getString("title"))
        assertEquals(123L, migrated.updatedAt)
        assertFalse(old.exists())
        assertEquals(2, WidgetSnapshotStore.read(app, a.pluginId, a.widgetId, "a")!!.root.getInt("schema"))
        assertNull(WidgetSnapshotStore.read(app, a.pluginId, a.widgetId, "b"))
    }

    @Test fun forgedLegacyPluginIdentityIsNeverMigrated() {
        val a = binding()
        val file = legacy(a, "Wrong owner")
        file.writeText(JSONObject(file.readText()).put("pluginId", "another.plugin").toString())
        assertNull(WidgetSnapshotStore.read(app, a.pluginId, a.widgetId, a.viewKey))
    }

    @Test fun interruptedSnapshotReplacementRecoversThePreviousCommittedView() {
        val a = binding()
        WidgetSnapshotStore.save(app, a.pluginId, a.widgetId, a.viewKey, payload("Committed"))
        val dir = File(app.filesDir, "plugin-widgets/${PluginStorageScope.hash("widget\u0000${a.pluginId}")}")
        val file = dir.listFiles()!!.single { it.extension == "json" }
        assertTrue(file.renameTo(File(dir, file.name + ".bak")))
        File(dir, file.name + ".tmp").writeText("incomplete")
        assertEquals("Committed", WidgetSnapshotStore.read(app, a.pluginId, a.widgetId, a.viewKey)!!.payload.getJSONObject("header").getString("title"))
        WidgetSnapshotStore.save(app, a.pluginId, a.widgetId, a.viewKey, payload("Next"))
        assertEquals("Next", WidgetSnapshotStore.read(app, a.pluginId, a.widgetId, a.viewKey)!!.payload.getJSONObject("header").getString("title"))
    }

    @Test fun changingOptionsInvalidatesOnlyThatPullInstance() {
        val a = binding("pull", template = "text")
        val b = binding("pull", template = "text")
        val declaration = PluginWidgetContract.declaration(pkg.manifest, "pull")!!
        listOf(a, b).forEach { WidgetSnapshotStore.savePull(app, it, declaration, payload("Saved")) }
        val changed = a.copy(options = JSONObject().put("count", 9))
        PluginWidgetBindings.save(app, changed)
        assertNull(WidgetSnapshotStore.read(app, changed, declaration))
        assertNotNull(WidgetSnapshotStore.read(app, b, declaration))
        // Explicit defaults and omitted defaults have the same data.query options identity.
        assertNotNull(WidgetSnapshotStore.read(app, b.copy(options = JSONObject().put("count", 2)), declaration))
    }

    @Test fun cancelledOrReconfiguredPullResultsCannotReviveOldData() {
        val a = binding("pull", template = "text")
        val declaration = PluginWidgetContract.declaration(pkg.manifest, "pull")!!
        PluginWidgetPull.storeResult(app, a, declaration, payload("Cancelled"), 1) { false }
        assertNull(WidgetSnapshotStore.read(app, a, declaration))
        val changed = a.copy(options = JSONObject().put("count", 8))
        PluginWidgetBindings.save(app, changed)
        PluginWidgetPull.storeResult(app, a, declaration, payload("Old query"), 2) { true }
        assertNull(WidgetSnapshotStore.read(app, changed, declaration))
    }

    @Test fun deletedPullResultCannotRecreateItsCache() {
        val a = binding("pull", template = "text")
        val declaration = PluginWidgetContract.declaration(pkg.manifest, "pull")!!
        PluginWidgetBindings.delete(app, a.appWidgetId)
        PluginWidgetPull.storeResult(app, a, declaration, payload("Deleted"), 1) { true }
        assertNull(WidgetSnapshotStore.read(app, a, declaration))
    }

    @Test fun deletingLastPullInstanceStopsBothJobsAndRemovesItsCache() {
        val a = binding("pull", template = "text")
        val b = binding("pull", template = "text")
        val declaration = PluginWidgetContract.declaration(pkg.manifest, "pull")!!
        WidgetSnapshotStore.savePull(app, a, declaration, payload("Saved"))
        PluginWidgetPullService.schedule(app, pkg.manifest.id)
        PluginWidgetPullService.requestImmediate(app, pkg.manifest.id)
        PluginWidgetMediumProvider().onDeleted(app, intArrayOf(a.appWidgetId))
        assertNull(WidgetSnapshotStore.read(app, a, declaration))
        assertEquals(2, jobs.allPendingJobs.size)
        PluginWidgetMediumProvider().onDeleted(app, intArrayOf(b.appWidgetId))
        assertTrue(jobs.allPendingJobs.isEmpty())
    }

    @Test fun replacingAtThePluginQuotaDoesNotDoubleCountTheOldSnapshot() {
        for (index in 0..3) WidgetSnapshotStore.save(app, pkg.manifest.id, "quota", "v$index", payload("Quota").put("footer", "x".repeat(60_000)))
        val before = WidgetSnapshotStore.totalBytes(app, pkg.manifest.id)
        WidgetSnapshotStore.save(app, pkg.manifest.id, "quota", "v0", payload("Quota").put("footer", "y".repeat(60_000)))
        assertEquals(before, WidgetSnapshotStore.totalBytes(app, pkg.manifest.id))
        WidgetSnapshotStore.clearWidget(app, pkg.manifest.id, "quota")
        assertEquals(0L, WidgetSnapshotStore.totalBytes(app, pkg.manifest.id))
    }

    @Test fun timerSelectionUsesTheBoundViewRatherThanTheLastPublishedView() {
        binding(view = "a")
        val end = System.currentTimeMillis() + 100_000
        publish("a", "Timer", end)
        publish("b", "Unbound")
        PluginWidgets.rescheduleCountdown(app)
        assertEquals(end + 1000, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtTime)
    }

    @Test fun newlyPublishedTimersScheduleAndReplaceBoundaryAlarm() {
        val saved = binding()
        val first = System.currentTimeMillis() + 100_000
        publish("a", "First", first)
        assertEquals(first + 1000, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtTime)
        val next = first + 200_000
        publish("a", "Next", next)
        assertEquals(next, WidgetSnapshotStore.read(app, saved, PluginWidgetContract.declaration(pkg.manifest, "push")!!)!!.payload.getJSONObject("timer").getLong("endAt"))
        assertEquals(1, PluginWidgetBindings.forPlugin(app, pkg.manifest.id).size)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        assertEquals(next + 1000, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtTime)
        publish("a", "Finished")
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun expiredTimerBoundaryStopsReschedulingAndDeletingCancelsTheAlarm() {
        val a = binding()
        val end = System.currentTimeMillis() + 1000
        publish("a", "Timer", end)
        // This path uses the JVM wall clock; advancing Robolectric uptime does not expire it.
        Thread.sleep((end - System.currentTimeMillis() + 50).coerceAtLeast(0))
        PluginWidgetSmallProvider().onReceive(app, Intent(PluginWidgets.ACTION_REFRESH))
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        publish("a", "Timer", System.currentTimeMillis() + 10_000)
        assertNotNull(shadowOf(alarms).peekNextScheduledAlarm())
        PluginWidgetMediumProvider().onDeleted(app, intArrayOf(a.appWidgetId))
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun disableImmediatelyHidesDataCancelsWorkAndPreservesConfiguration() {
        val saved = binding()
        publish("a", "Private", System.currentTimeMillis() + 100_000)
        PluginWidgetPullService.schedule(app, pkg.manifest.id)
        PluginWidgetPullService.requestImmediate(app, pkg.manifest.id)
        AcademicProviderRegistry.setEnabled(pkg.manifest.id, false)
        assertTrue("Disabled plugin work must stop", jobs.allPendingJobs.isEmpty())
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertEquals(saved.viewKey, PluginWidgetBindings.read(app, saved.appWidgetId)?.viewKey)
        assertTrue(shadowOf(manager).getViewFor(saved.appWidgetId).findViewById<TextView>(R.id.plugin_widget_empty).text.contains("停用"))
        PluginWidgets.handle(app, PluginWidgets.ACTION_REFRESH, saved.appWidgetId)
        assertTrue(jobs.allPendingJobs.isEmpty())
    }

    @Test fun enablingRestoresTheSavedViewAndCountdownWhileRequestingFreshPullData() {
        val push = binding(template = "text")
        binding("pull", template = "text")
        val end = System.currentTimeMillis() + 100_000
        publish("a", "Restored", end)
        AcademicProviderRegistry.setEnabled(pkg.manifest.id, false)
        AcademicProviderRegistry.setEnabled(pkg.manifest.id, true)
        assertEquals("Restored", shadowOf(manager).getViewFor(push.appWidgetId).findViewById<TextView>(R.id.plugin_widget_main).text.toString())
        assertEquals(end + 1000, shadowOf(alarms).peekNextScheduledAlarm()?.triggerAtTime)
        assertEquals(pkg.manifest.id, jobs.allPendingJobs.single().extras.getString("pluginId"))
    }

    @Test fun narrowGridWithThreeItemsShowsTheSecondRow() {
        val saved = binding(template = "grid")
        val data = payload("One").put("rows", JSONArray((1..3).map { JSONObject().put("id", "r$it").put("title", "Item $it") }))
        val snapshot = WidgetSnapshotStore.save(app, saved.pluginId, saved.widgetId, saved.viewKey, data)
        val root = PluginWidgetRenderer.views(app, pkg, saved, snapshot, 1000, 180, 140).apply(app, null)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setContentView(root)
        val width = (180 * app.resources.displayMetrics.density).toInt()
        val height = (140 * app.resources.displayMetrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertTrue(root.findViewById<View>(R.id.plugin_widget_grid_row_2).isShown)
        val third = root.findViewById<TextView>(R.id.plugin_widget_cell_4)
        assertEquals("Item 3", third.text.toString())
        assertTrue(third.isShown && third.height > 0)
    }
}
