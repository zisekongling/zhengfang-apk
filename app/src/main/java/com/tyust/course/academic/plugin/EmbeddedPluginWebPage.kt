package com.tyust.course.academic.plugin

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tyust.course.academic.AcademicSession
import com.tyust.course.ui.system.SystemDialogButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable internal fun EmbeddedPluginWebPage(pkg: PluginPackage, owner: AcademicSession, declaration: JSONObject, params: JSONObject, onClose: () -> Unit, scopeKey: String) {
    val app = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempted by rememberSaveable(scopeKey) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("正在打开内置浏览器…") }
    // Re-read on resume: the user may have just installed the component.
    var component by remember { mutableStateOf(BrowserComponentHost.status(app)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { component = BrowserComponentHost.status(app) }
    val close by rememberUpdatedState(onClose)
    val browser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { close() }
    suspend fun open() {
        loading = true
        try {
            val result = PluginEmbeddedBrowser.preparePage(app, pkg, owner, declaration, params)
            result.optString("migrationMessage").takeIf { it.isNotBlank() }?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
            browser.launch(PluginEmbeddedBrowser.activity(result.getString("handle")))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = (e as? PluginException)?.message ?: "内置浏览器未能打开，请重试" }
        finally { loading = false }
    }
    LaunchedEffect(scopeKey, component.ready) { if (!attempted && component.ready) { attempted = true; open() } }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!component.ready) {
            Text(component.message)
            if (component.state != BrowserComponentHost.State.UNSUPPORTED_SDK)
                SystemDialogButton(primary = true, onClick = { app.startActivity(BrowserComponentActivity.intent(app)) }) { Text("安装内置浏览器") }
            SystemDialogButton(onClick = onClose) { Text("返回") }
            return@Column
        }
        Text(message)
        if (loading) CircularProgressIndicator()
        else {
            SystemDialogButton(onClick = { scope.launch { open() } }) { Text("重新打开") }
            SystemDialogButton(onClick = onClose) { Text("返回") }
        }
    }
}
