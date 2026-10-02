package com.arcxya09.touch.update

import org.json.JSONObject

data class UpdateManifest(val versionCode: Long, val versionName: String, val packageName: String,
    val minSdk: Int, val releaseTag: String, val apkAssetName: String, val apkSize: Long, val sha256: String, val changelog: String,
    val prerelease: Boolean = false) {
    val downloadUrl get() = "https://github.com/arcxya09/touch/releases/download/$releaseTag/$apkAssetName"
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
    companion object {
        const val URL = "https://github.com/arcxya09/touch/releases/latest/download/update.json"
        fun parse(json: JSONObject): UpdateManifest {
            require(json.getInt("schemaVersion") == 1) { "暂不支持此更新清单" }
            val manifest = UpdateManifest(json.getLong("versionCode"), json.getString("versionName"), json.getString("packageName"),
                json.getInt("minSdk"), json.getString("releaseTag"), json.getString("apkAssetName"), json.getLong("apkSize"),
                json.getString("sha256"), json.getString("changelog"))
            require(manifest.packageName == "com.arcxya09.touch") { "更新包身份不匹配" }
            require(manifest.versionCode > 0 && manifest.minSdk >= 29) { "更新版本信息异常" }
            require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(manifest.releaseTag)) { "版本标签不合法" }
            require(manifest.releaseTag == "v${manifest.versionName}") { "版本信息不一致" }
            require(manifest.apkAssetName == "touch.apk") { "安装包名称异常" }
            require(manifest.apkSize in 1..300L * 1024 * 1024) { "安装包大小异常" }
            require(Regex("[0-9a-f]{64}").matches(manifest.sha256)) { "校验信息异常" }
            require(manifest.changelog.length <= 20000) { "更新说明过长" }
            return manifest
        }
    }
}
