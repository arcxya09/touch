package com.arcxya09.touch

import com.arcxya09.touch.update.UpdateManifest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private fun json() = JSONObject().put("schemaVersion", 1).put("versionCode", 2).put("versionName", "1.0.1")
        .put("packageName", "com.arcxya09.touch").put("minSdk", 29).put("releaseTag", "v1.0.1")
        .put("apkAssetName", "touch.apk").put("apkSize", 100).put("sha256", "a".repeat(64)).put("changelog", "修复问题")
    @Test fun comparesNumericVersionAndCompatibility() {
        val manifest = UpdateManifest.parse(json())
        assertTrue(manifest.newerThan(1, 29))
        assertFalse(manifest.newerThan(2, 37))
        assertFalse(manifest.newerThan(3, 37))
        assertFalse(manifest.newerThan(1, 28))
        assertEquals("https://github.com/arcxya09/touch/releases/download/v1.0.1/touch.apk", manifest.downloadUrl)
    }
    @Test fun rejectsMalformedAndCrossRepositoryAssets() {
        for ((key, value) in listOf("packageName" to "other.app", "releaseTag" to "../other", "apkAssetName" to "https://bad.example/app.apk", "sha256" to "invalid")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(json().put(key, value)) }
        }
    }
}
