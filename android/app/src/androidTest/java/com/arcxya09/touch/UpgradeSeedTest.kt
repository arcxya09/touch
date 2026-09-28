package com.arcxya09.touch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.arcxya09.touch.timer.Pomodoro
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Used with a temporary, same-certificate release fixture only. */
class UpgradeSeedTest {
    @Test fun seedPersistentState() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("upgradeProbe") == "seed")
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        app.repository.login(args.getString("touchTestUser")!!, args.getString("touchTestPassword")!!)
        app.repository.sync()
        val cid = app.repository.conversations().single().id
        app.repository.history(cid)
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", "0125").toString())
        File(app.filesDir, "privacy.enabled").writeText("1")
        app.getSharedPreferences("gesture_attempts", Context.MODE_PRIVATE).edit().clear().commit()
        Pomodoro(app).apply { configure(25, 5); start() }
        app.database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        val probe = JSONObject().put("user", app.repository.api.user!!.id)
            .put("cursor", app.database.cache().get("meta", "cursor")!!.json)
            .put("messages", app.repository.messages(cid).size)
            .put("secure", com.arcxya09.touch.data.sha256(File(app.filesDir, "datastore/touch_secure.preferences_pb")))
            .put("wallEnd", app.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).getLong("wallEnd", 0))
        File(app.filesDir, "upgrade-probe.json").writeText(probe.toString())
        app.getExternalFilesDir(null)!!.mkdirs()
    }
}
