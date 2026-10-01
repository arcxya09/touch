package com.arcxya09.touch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.arcxya09.touch.timer.Pomodoro
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Used with a temporary, same-certificate release fixture only. */
class UpgradeSeedTest {
    @Test fun seedPersistentState() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("upgradeProbe") == "seed") { "Run this dedicated test with upgradeProbe=seed" }
        val fixture = requireNotNull(args.getString("fixtureBase")) { "fixtureBase is required" }
        val uri = java.net.URI(fixture)
        require(uri.host == "127.0.0.1" && uri.scheme == "http" && BuildConfig.API_BASE == fixture) { "Seed only a build configured for the isolated loopback fixture" }
        val username = requireNotNull(args.getString("touchTestUser"))
        require(username.startsWith("verify_local_")) { "Only disposable fixture accounts are allowed" }
        val password = requireNotNull(args.getString("touchTestPassword"))
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        app.repository.initialize()
        app.repository.login(username, password)
        app.repository.sync()
        val conversation = app.repository.conversations().single()
        val cid = conversation.id
        app.repository.history(cid)
        val fixtureMessage = app.repository.messages(cid).firstOrNull { it.kind == "text" && it.text.isNotBlank() }
            ?: error("The isolated fixture must contain a text message before seeding")
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", "0125").toString())
        File(app.filesDir, "privacy.enabled").writeText("1")
        app.getSharedPreferences("gesture_attempts", Context.MODE_PRIVATE).edit().clear().commit()
        Pomodoro(app).apply { configure(25, 5); start() }
        app.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        val probe = JSONObject().put("user", app.repository.api.user!!.id)
            .put("versionCode", app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode)
            .put("peerName", conversation.peer.name).put("messageText", fixtureMessage.text)
            .put("cursor", app.database.cache().get("meta", "cursor")!!.json)
            .put("messages", app.repository.messages(cid).size)
            .put("secure", com.arcxya09.touch.data.sha256(File(app.filesDir, "datastore/touch_secure.preferences_pb")))
            .put("wallEnd", app.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).getLong("wallEnd", 0))
        File(app.filesDir, "upgrade-probe.json").writeText(probe.toString())
        app.getExternalFilesDir(null)!!.mkdirs()
    }
}
