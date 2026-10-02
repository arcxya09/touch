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
import org.json.JSONArray
import java.io.File
import java.io.ByteArrayOutputStream

class Updater(activity: Activity, private val api: Api) {
    private val context = activity.applicationContext
    private val activityReference = java.lang.ref.WeakReference(activity)
    private val preferences = activity.getSharedPreferences("update_checks", Activity.MODE_PRIVATE)
    fun due(): Boolean = !BuildConfig.DEBUG && System.currentTimeMillis() >= preferences.getLong("next_check", 0)
    suspend fun check(includePrereleases: Boolean = false): UpdateManifest? = withContext(Dispatchers.IO) {
        check(!BuildConfig.DEBUG) { "调试版本不使用正式更新通道" }
        try {
            val manifests = mutableListOf<UpdateManifest>()
            val stable = readJson(UpdateManifest.URL, 65536, allowMissing = includePrereleases)
            if (stable != null) manifests += UpdateManifest.parse(JSONObject(stable))
            if (includePrereleases) {
                val releases = JSONArray(checkNotNull(readJson(UpdateChannel.RELEASES_URL, 2 * 1024 * 1024)))
                for (tag in UpdateChannel.prereleaseTags(releases)) {
                    currentCoroutineContext().ensureActive()
                    val url = "https://github.com/arcxya09/touch/releases/download/$tag/update.json"
                    val manifest = UpdateManifest.parse(JSONObject(checkNotNull(readJson(url, 65536))))
                    require(manifest.releaseTag == tag) { "预发布清单与版本标签不一致" }
                    manifests += manifest.copy(prerelease = true)
                }
            }
            val result = UpdateChannel.select(manifests, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT)
            preferences.edit().putLong("next_check", System.currentTimeMillis() + 86400000).putInt("failures", 0).apply()
            result
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val failures = (preferences.getInt("failures", 0) + 1).coerceAtMost(6)
            preferences.edit().putInt("failures", failures)
                .putLong("next_check", System.currentTimeMillis() + (900000L shl (failures - 1))).apply()
            throw error
        }
    }
    private suspend fun readJson(url: String, limit: Int, allowMissing: Boolean = false): String? {
        val request = Request.Builder().url(url).tag(this).header("Accept", "application/json").build()
        return api.execute(request).use { response ->
            if (allowMissing && response.code == 404) return@use null
            check(response.isSuccessful) { if (response.code == 404) "暂无可用的更新清单" else "更新源暂不可用（${response.code}）" }
            val body = response.body ?: error("更新信息为空")
            check(body.contentLength() <= limit) { "更新信息过大" }
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= limit) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= limit) { "更新信息过大" }
            bytes.toString(Charsets.UTF_8)
        }
    }
    suspend fun download(manifest: UpdateManifest, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
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
        val manager = context.packageManager
        val incoming = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("安装包无法解析")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(incoming.packageName == context.packageName && incoming.longVersionCode == manifest.versionCode
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
    suspend fun verifyForInstall(file: File, manifest: UpdateManifest) = withContext(Dispatchers.IO) {
        try { verify(file, manifest) } catch (error: Exception) {
            if (error is CancellationException) throw error
            file.delete(); throw error
        }
        currentCoroutineContext().ensureActive()
    }
    fun launchInstaller(file: File) {
        val activity = activityReference.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: error("页面已关闭，请重新打开更新页面")
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
