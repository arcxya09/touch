package com.arcxya09.touch.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.arcxya09.touch.BuildConfig
import com.arcxya09.touch.data.Api
import com.arcxya09.touch.data.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream

class Updater(private val activity: Activity, private val api: Api) {
    private val preferences = activity.getSharedPreferences("update_checks", Activity.MODE_PRIVATE)
    fun due(): Boolean = !BuildConfig.DEBUG && System.currentTimeMillis() >= preferences.getLong("next_check", 0)
    suspend fun check(): UpdateManifest? = withContext(Dispatchers.IO) {
        check(!BuildConfig.DEBUG) { "调试版本不使用正式更新通道" }
        try {
            val request = Request.Builder().url(UpdateManifest.URL).tag(this@Updater).header("Accept", "application/json").build()
            val manifest = api.execute(request).use { response ->
                check(response.isSuccessful) { if (response.code == 404) "暂无可用的正式更新清单" else "更新源暂不可用（${response.code}）" }
                val body = response.body ?: error("更新清单为空")
                check(body.contentLength() <= 65536) { "更新清单过大" }
                val bytes = body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (output.size() <= 65536) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 65537 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                check(bytes.size <= 65536) { "更新清单过大" }
                UpdateManifest.parse(JSONObject(bytes.toString(Charsets.UTF_8)))
            }
            preferences.edit().putLong("next_check", System.currentTimeMillis() + 86400000).putInt("failures", 0).apply()
            if (manifest.versionCode <= BuildConfig.VERSION_CODE) return@withContext null
            require(manifest.minSdk <= Build.VERSION.SDK_INT) { "新版本暂不兼容当前系统" }
            manifest
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val failures = (preferences.getInt("failures", 0) + 1).coerceAtMost(6)
            preferences.edit().putInt("failures", failures)
                .putLong("next_check", System.currentTimeMillis() + (900000L shl (failures - 1))).apply()
            throw error
        }
    }
    suspend fun download(manifest: UpdateManifest, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "touch-${manifest.versionCode}.apk")
        if (target.exists()) {
            if (runCatching { verify(target, manifest) }.isSuccess) return@withContext target
            target.delete()
        }
        val partial = File(directory, "download-${java.util.UUID.randomUUID()}.part")
        try {
            api.execute(Request.Builder().url(manifest.downloadUrl).tag(this@Updater).build()).use { response ->
                check(response.isSuccessful) { "安装包下载失败（${response.code}）" }
                response.body!!.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var length = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        length += count
                        check(length <= manifest.apkSize) { "安装包大小异常" }
                        output.write(buffer, 0, count)
                        progress(length.toFloat() / manifest.apkSize)
                    }
                } }
            }
            currentCoroutineContext().ensureActive()
            verify(partial, manifest)
            check(partial.renameTo(target)) { "安装包保存失败" }
            target
        } finally { partial.delete() }
    }
    @Suppress("DEPRECATION")
    fun verify(file: File, manifest: UpdateManifest) {
        check(file.length() == manifest.apkSize && sha256(file) == manifest.sha256) { "安装包完整性校验失败" }
        val manager = activity.packageManager
        val incoming = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("安装包无法解析")
        val installed = manager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(incoming.packageName == activity.packageName && incoming.longVersionCode == manifest.versionCode
            && incoming.longVersionCode > installed.longVersionCode && incoming.versionName == manifest.versionName) { "安装包身份或版本不匹配" }
        check(incoming.applicationInfo?.minSdkVersion == manifest.minSdk) { "安装包系统要求不匹配" }
        val received = incoming.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val trusted = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        check(received.isNotEmpty() && received == trusted) { "安装包签名不匹配" }
    }
    fun cancel() {
        (api.client.dispatcher.queuedCalls() + api.client.dispatcher.runningCalls())
            .filter { it.request().tag() === this }.forEach { it.cancel() }
    }
    fun install(file: File, manifest: UpdateManifest) {
        try { verify(file, manifest) } catch (error: Exception) { file.delete(); throw error }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
