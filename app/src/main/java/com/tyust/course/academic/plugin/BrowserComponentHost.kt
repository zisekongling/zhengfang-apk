package com.tyust.course.academic.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tyust.course.BuildConfig
import com.tyust.course.update.UpdateManifestVerifier
import com.tyust.course.update.UpdateTransfer
import com.tyust.course.update.awaitResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.zip.ZipFile

/** Presence, trust and installation of the on-demand GeckoView APK. No Gecko code runs in the App. */
object BrowserComponentHost {
    enum class State { UNSUPPORTED_SDK, MISSING, UNTRUSTED, OUTDATED, READY }
    data class Status(val state: State, val versionName: String = "", val installedAt: Long = 0) {
        val ready get() = state == State.READY
        val message get() = when (state) {
            State.UNSUPPORTED_SDK -> "内置浏览器需要 Android 8.0 或更新版本，App 其他功能可继续使用"
            State.MISSING -> "此功能需要安装“内置浏览器”组件（约 85 MB），App 其他功能可继续使用"
            State.UNTRUSTED -> "已安装的内置浏览器不是本 App 的官方签名，请卸载后重新安装"
            State.OUTDATED -> "内置浏览器组件版本过旧，请更新组件"
            State.READY -> "内置浏览器 $versionName 已安装"
        }
    }
    const val PACKAGE = BuildConfig.BROWSER_PACKAGE
    private const val REPO = "znjhahaha/zhengfang-apk"
    private const val MAX_BYTES = 200L * 1024 * 1024

    @Suppress("DEPRECATION")
    private fun info(app: Context): PackageInfo? = runCatching { app.packageManager.getPackageInfo(PACKAGE, 0) }.getOrNull()
    @Suppress("DEPRECATION") private fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    fun status(app: Context): Status {
        if (Build.VERSION.SDK_INT < 26) return Status(State.UNSUPPORTED_SDK)
        val info = info(app) ?: return Status(State.MISSING)
        val name = info.versionName.orEmpty()
        if (!BrowserComponent.sameSigner(app, PACKAGE)) return Status(State.UNTRUSTED, name)
        if (code(info) < BuildConfig.BROWSER_MIN_VERSION) return Status(State.OUTDATED, name)
        return Status(State.READY, name, info.firstInstallTime)
    }

    /** Cheap per-cookie check: the install a profile migrated into. A reinstall wipes Gecko data. */
    fun installIdentity(app: Context): String? = info(app)?.let { "${it.firstInstallTime}" }

    fun require(app: Context) {
        val status = status(app)
        if (!status.ready) throw PluginException(PluginErrorCode.UNSUPPORTED, status.message)
    }

    fun engine(app: Context): String = info(app)?.versionName?.substringBefore('.')?.let { "GeckoView $it" } ?: "内置浏览器未安装"

    /** Release asset for this App version. Mirrors are tried in order; only same-signer APKs install. */
    fun candidates(abi: String): List<String> {
        val original = "https://github.com/$REPO/releases/download/v${BuildConfig.VERSION_NAME}/browser-$abi-release.apk"
        return listOf("https://gh-proxy.com/$original", "https://ghproxy.net/$original", original)
    }
    fun abi(): String? = listOf("arm64-v8a", "armeabi-v7a").firstOrNull { it in Build.SUPPORTED_ABIS }
        ?: if (BuildConfig.DEBUG) Build.SUPPORTED_ABIS.firstOrNull() else null

    private fun staged(app: Context) = File(app.filesDir, "app-updates/browser-component.apk")

    suspend fun download(app: Context, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        val abi = abi() ?: throw PluginException(PluginErrorCode.UNSUPPORTED, "当前设备架构不支持内置浏览器")
        val target = staged(app).apply { parentFile?.mkdirs() }
        val partial = File(target.path + ".part")
        val client = UpdateTransfer.client()
        var failure: Exception? = null
        for (start in candidates(abi)) {
            currentCoroutineContext().ensureActive()
            try {
                var url = start
                for (redirect in 0..5) {
                    if (!UpdateManifestVerifier.validUrl(url)) throw PluginException(PluginErrorCode.UNTRUSTED_URL, "下载跳转地址不受支持")
                    val next = client.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build()).awaitResponse().use { response ->
                        if (response.code in setOf(301, 302, 303, 307, 308))
                            return@use response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                                ?: throw PluginException(PluginErrorCode.NETWORK_RETRYABLE, "下载线路返回了无效跳转")
                        if (response.code != 200) throw PluginException(PluginErrorCode.NETWORK_RETRYABLE, "下载线路暂时不可用（HTTP ${response.code}）")
                        val body = response.body ?: throw PluginException(PluginErrorCode.NETWORK_RETRYABLE, "下载线路没有返回文件")
                        if (body.contentLength() > MAX_BYTES) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "组件文件超过上限")
                        var received = 0L
                        partial.outputStream().use { output -> body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                received += n
                                if (received > MAX_BYTES) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "组件文件超过上限")
                                output.write(buffer, 0, n); progress(received)
                            }
                        } }
                        null
                    } ?: break
                    url = next
                }
                verify(app, partial, abi)
                target.delete()
                if (!partial.renameTo(target)) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "无法保存组件安装包")
                return@withContext target
            } catch (e: kotlinx.coroutines.CancellationException) { partial.delete(); throw e }
            catch (e: Exception) { partial.delete(); failure = e; if ((e as? PluginException)?.code == PluginErrorCode.BAD_SIGNATURE) break }
        }
        throw failure as? PluginException ?: PluginException(PluginErrorCode.NETWORK_RETRYABLE, "下载线路暂时不可用，请稍后重试", failure)
    }

    /** Imports an APK the user picked, e.g. a CI artifact during testing. Same checks as a download. */
    suspend fun stage(app: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val target = staged(app).apply { parentFile?.mkdirs() }
        val partial = File(target.path + ".part")
        try {
            val stream = app.contentResolver.openInputStream(uri) ?: throw PluginException(PluginErrorCode.NOT_OPEN, "无法读取所选文件")
            stream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; if (total > MAX_BYTES) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "组件文件超过上限")
                    output.write(buffer, 0, n)
                }
            } }
            verify(app, partial, null)
            target.delete()
            if (!partial.renameTo(target)) throw PluginException(PluginErrorCode.RESOURCE_LIMIT, "无法保存组件安装包")
            target
        } catch (e: Exception) { partial.delete(); throw e }
    }

    @Suppress("DEPRECATION")
    internal fun verify(app: Context, file: File, abi: String?) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = app.packageManager.getPackageArchiveInfo(file.path, flags) ?: throw PluginException(PluginErrorCode.VALIDATION_FAILED, "无法识别组件安装包")
        if (apk.packageName != PACKAGE) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "这不是本 App 的内置浏览器组件")
        if (code(apk) < BuildConfig.BROWSER_MIN_VERSION) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "组件版本过旧，App 需要 ${BuildConfig.BROWSER_MIN_VERSION} 或更新版本")
        fun signers(info: PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()
        val own = signers(app.packageManager.getPackageInfo(app.packageName, flags))
        if (own.isEmpty() || signers(apk) != own) throw PluginException(PluginErrorCode.BAD_SIGNATURE, "组件安装包不是本 App 的官方签名")
        val abis = runCatching { ZipFile(file).use { zip -> zip.entries().asSequence()
            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }.map { it.name.split('/')[1] }.toSet() } }.getOrDefault(emptySet())
        if (abis.none { it in Build.SUPPORTED_ABIS } || abi != null && abi !in abis)
            throw PluginException(PluginErrorCode.VALIDATION_FAILED, "组件安装包架构与设备不一致")
    }

    /** Returns false when the user must first allow installs from this App. */
    fun install(app: Context, file: File): Boolean {
        verify(app, file, null)
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }

    fun uninstall(app: Context) {
        app.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun clearStaged(app: Context) { staged(app).delete() }
}
