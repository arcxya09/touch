package com.arcxya09.touch

import com.arcxya09.touch.update.UpdateChannel
import com.arcxya09.touch.update.UpdateManifest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateChannelTest {
    private fun manifest(code: Long, pre: Boolean = false, sdk: Int = 29) =
        UpdateManifest(code, "2.0.0", "com.arcxya09.touch", sdk, "v2.0.0", "touch.apk", 100, "a".repeat(64), "", pre)

    @Test fun choosesHighestVersionCodeWithoutDowngradingOrIgnoringStableReleases() {
        val stable = manifest(18)
        val pre = manifest(20, true)
        assertEquals(pre, UpdateChannel.select(listOf(pre, stable, manifest(16, true)), 17, 36))
        assertEquals(stable, UpdateChannel.select(listOf(manifest(16, true), stable), 17, 36))
        assertNull(UpdateChannel.select(listOf(pre, stable), 20, 36))
        assertEquals(stable, UpdateChannel.select(listOf(stable, manifest(18, true)), 17, 36))
    }

    @Test fun usesCompatibleUpdateAndReportsUnsupportedNewerVersions() {
        val stable = manifest(18)
        assertEquals(stable, UpdateChannel.select(listOf(manifest(20, true, 37), stable), 17, 36))
        assertThrows(IllegalArgumentException::class.java) {
            UpdateChannel.select(listOf(manifest(20, true, 37)), 17, 36)
        }
    }

    @Test fun onlyPublishedPrereleasesWithUploadedManifestsAreCandidates() {
        fun release(tag: String, draft: Boolean = false, pre: Boolean = true, uploaded: Boolean = true) =
            JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", pre).put("assets",
                JSONArray().put(JSONObject().put("name", "update.json").put("state", if (uploaded) "uploaded" else "new")))
        val feed = JSONArray().put(release("v2.0.0")).put(release("v2.0.0"))
            .put(release("v3.0.0", draft = true)).put(release("v1.0.14", pre = false))
            .put(release("v2.1.0", uploaded = false)).put(release("../../other"))
        assertEquals(listOf("v2.0.0"), UpdateChannel.prereleaseTags(feed))
    }
}
