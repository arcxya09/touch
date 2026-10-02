package com.arcxya09.touch.update

import org.json.JSONArray

internal object UpdateChannel {
    const val RELEASES_URL = "https://api.github.com/repos/arcxya09/touch/releases?per_page=100"

    fun prereleaseTags(releases: JSONArray): List<String> = buildList {
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.getBoolean("draft") || !release.getBoolean("prerelease")) continue
            val tag = release.getString("tag_name")
            if (!Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag)) continue
            val assets = release.optJSONArray("assets") ?: continue
            if ((0 until assets.length()).any {
                    val asset = assets.getJSONObject(it)
                    asset.optString("name") == "update.json" && asset.optString("state") == "uploaded"
                }) add(tag)
        }
    }.distinct()

    fun select(manifests: List<UpdateManifest>, installed: Long, sdk: Int): UpdateManifest? {
        val newer = manifests.filter { it.versionCode > installed }
        val compatible = newer.filter { it.minSdk <= sdk }
        require(newer.isEmpty() || compatible.isNotEmpty()) { "新版本暂不兼容当前系统" }
        // Prefer a stable release on a tie; publication time and semantic labels never cause a downgrade.
        return compatible.maxWithOrNull(compareBy<UpdateManifest> { it.versionCode }.thenBy { !it.prerelease })
    }
}
