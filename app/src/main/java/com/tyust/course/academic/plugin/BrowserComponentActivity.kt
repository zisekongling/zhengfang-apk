package com.tyust.course.academic.plugin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** Installs, updates or removes the on-demand GeckoView APK. Plugins never trigger installs themselves. */
class BrowserComponentActivity : ComponentActivity() {
    private var generation by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); generation++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CourseSelectorTheme { GlassWindowHost { Page() } } }
    }

    @Composable private fun Page() {
        val scope = rememberCoroutineScope()
        val status = remember(generation) { BrowserComponentHost.status(this) }
        var job by remember { mutableStateOf<Job?>(null) }
        var bytes by remember { mutableLongStateOf(0L) }
        var ready by remember { mutableStateOf<File?>(null) }
        var message by remember { mutableStateOf("") }
        fun run(block: suspend () -> Unit) {
            if (job?.isActive == true) return
            message = ""
            job = scope.launch {
                try { block() }
                catch (e: CancellationException) { message = "已取消"; throw e }
                catch (e: Exception) { message = (e as? PluginException)?.message ?: "操作未完成，请稍后重试" }
            }
        }
        fun install(file: File) {
            ready = file
            try { if (!BrowserComponentHost.install(this, file)) message = "请允许安装此来源，返回后再点“安装”" }
            catch (e: Exception) { message = (e as? PluginException)?.message ?: "无法打开安装器" }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) run { install(BrowserComponentHost.stage(this, uri)) }
        }
        LaunchedEffect(status.ready) { if (status.ready) { BrowserComponentHost.clearStaged(this@BrowserComponentActivity); ready = null } }
        val busy = job?.isActive == true
        GlassPageScaffold(title = "内置浏览器", onBack = { finish() }) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                InsetGroupedSection(header = "组件状态", footer = "学习通等声明内置浏览器的插件用它打开网页和运行原脚本。" +
                    "未安装时，原生登录、课程与签到等功能照常使用。组件与 App 使用同一签名，只为本 App 提供服务。") {
                    InsetGroupedRow(title = if (status.ready) "已安装" else "未就绪", subtitle = status.message, showDivider = false)
                }
                if (busy && bytes > 0) {
                    Text("已下载 ${bytes / (1024 * 1024)} MB")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (message.isNotBlank()) Text(message)
                if (status.state != BrowserComponentHost.State.UNSUPPORTED_SDK) {
                    if (!status.ready && status.state != BrowserComponentHost.State.UNTRUSTED) {
                        SystemDialogButton(primary = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("browser-download"), onClick = {
                            run { bytes = 0; install(BrowserComponentHost.download(this@BrowserComponentActivity) { bytes = it }) }
                        }) { Text(if (status.state == BrowserComponentHost.State.OUTDATED) "下载并更新" else "下载并安装") }
                        ready?.let { file -> SystemDialogButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { install(file) }) { Text("安装") } }
                        SystemDialogButton(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("browser-pick"), onClick = {
                            picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                        }) { Text("从文件安装组件") }
                    }
                    if (busy) SystemDialogButton(modifier = Modifier.fillMaxWidth(), onClick = { job?.cancel() }) { Text("取消下载") }
                    if (status.state != BrowserComponentHost.State.MISSING)
                        SystemDialogButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { BrowserComponentHost.uninstall(this@BrowserComponentActivity) }) {
                            Text("卸载组件（会清除内置浏览器中的登录）")
                        }
                }
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, BrowserComponentActivity::class.java)
            .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
