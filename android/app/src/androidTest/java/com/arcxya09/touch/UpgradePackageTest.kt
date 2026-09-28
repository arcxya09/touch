package com.arcxya09.touch

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.arcxya09.touch.update.UpdateManifest
import com.arcxya09.touch.update.Updater
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class UpgradePackageTest {
    @Test fun checksRealApkIdentityHashAndCertificate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upgradeProbe") == "packages")
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val dir = app.getExternalFilesDir(null)!!
        val manifest = UpdateManifest.parse(JSONObject(File(dir, "update.json").readText()))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val updater = Updater(activity, app.repository.api)
                updater.verify(File(dir, "touch.apk"), manifest)
                assertThrows(IllegalStateException::class.java) { updater.verify(File(dir, "touch.apk"), manifest.copy(sha256 = "0".repeat(64))) }
                assertThrows(IllegalStateException::class.java) { updater.verify(File(dir, "touch.apk"), manifest.copy(apkSize = manifest.apkSize + 1)) }
                for ((name, expected) in listOf("wrong-signer.apk" to "签名", "wrong-package.apk" to "身份")) {
                    val file = File(dir, name)
                    val other = manifest.copy(apkSize = file.length(), sha256 = com.arcxya09.touch.data.sha256(file))
                    val failure = assertThrows(IllegalStateException::class.java) { updater.verify(file, other) }
                    assertTrue(failure.message!!.contains(expected))
                }
            }
        }
    }
}
