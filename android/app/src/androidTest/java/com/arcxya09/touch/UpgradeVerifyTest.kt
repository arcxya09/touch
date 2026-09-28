package com.arcxya09.touch

import android.content.Context
import android.content.Intent
import android.graphics.Point
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** No app-class references: this test also works after the target is R8-minified. */
class UpgradeVerifyTest {
    @Test fun installedUpgradePreservesStateAndLocksFirstLaunch() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upgradeProbe") == "verify")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = JSONObject(File(context.filesDir, "upgrade-probe.json").readText())
        assertEquals(3L, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
        val encrypted = File(context.filesDir, "datastore/touch_secure.preferences_pb").readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(encrypted).joinToString("") { "%02x".format(it) }
        assertEquals(probe.getString("secure"), hash)
        assertTrue(File(context.filesDir, "privacy.enabled").exists())
        val timer = context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE)
        assertEquals(probe.getLong("wallEnd"), timer.getLong("wallEnd", 0))
        assertTrue(timer.getBoolean("running", false))
        context.startActivity(Intent().setClassName(context.packageName, "com.arcxya09.touch.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.wait(Until.hasObject(By.text("此刻，只做一件事")), 15000))
        assertFalse(device.hasObject(By.text("验收设备B")))
        assertFalse(device.hasObject(By.text("欢迎回来")))
        val density = context.resources.displayMetrics.density
        val left = 28 * density
        val width = device.displayWidth - 2 * left
        val top = device.findObject(By.text("此刻，只做一件事")).visibleBounds.bottom + 28 * density
        fun point(column: Int, row: Int) = Point((left + (column + 0.5f) * width / 3).toInt(), (top + (row + 0.5f) * width / 3).toInt())
        device.swipe(arrayOf(point(0, 0), point(1, 0), point(2, 0), point(2, 1)), 35)
        assertTrue(device.wait(Until.hasObject(By.text("验收设备B")), 15000))
        val database = context.getDatabasePath("touch.db").readBytes()
        assertFalse(database.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
        assertFalse(database.toString(Charsets.ISO_8859_1).contains(probe.getString("user")))
        device.findObject(By.text("验收设备B")).click()
        assertTrue(device.wait(Until.hasObject(By.text("生产联调：你好，Touch")), 15000))
    }
}
