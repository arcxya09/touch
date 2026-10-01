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
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** No app-class references: this test also works after the target is R8-minified. */
class UpgradeVerifyTest {
    @Test fun installedUpgradePreservesStateAndLocksFirstLaunch() {
        val arguments = InstrumentationRegistry.getArguments()
        require(arguments.getString("upgradeProbe") == "verify") { "Run this dedicated test with upgradeProbe=verify" }
        val expectedVersion = requireNotNull(arguments.getString("expectedVersionCode")?.toLongOrNull()) { "expectedVersionCode is required" }
        require(expectedVersion > 0)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = JSONObject(File(context.filesDir, "upgrade-probe.json").readText())
        assertEquals(expectedVersion, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
        assertTrue("The target must be newer than the seeded baseline", expectedVersion > probe.getLong("versionCode"))
        val peerName = probe.getString("peerName")
        val messageText = probe.getString("messageText")
        val encrypted = File(context.filesDir, "datastore/touch_secure.preferences_pb").readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(encrypted).joinToString("") { "%02x".format(it) }
        assertEquals(probe.getString("secure"), hash)
        assertTrue(File(context.filesDir, "privacy.enabled").exists())
        val timer = context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE)
        // MY_PACKAGE_REPLACED reschedules using elapsed time; wall conversion may differ by milliseconds.
        assertTrue(kotlin.math.abs(probe.getLong("wallEnd") - timer.getLong("wallEnd", 0)) <= 1000)
        assertTrue(timer.getBoolean("running", false))
        context.startActivity(Intent().setClassName(context.packageName, "com.arcxya09.touch.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.wait(Until.hasObject(By.text("此刻，只做一件事")), 15000))
        assertFalse(device.hasObject(By.text(peerName)))
        assertFalse(device.hasObject(By.text("欢迎回来")))
        val density = context.resources.displayMetrics.density
        // Derive the dial from its ordinary timer semantics; do not expose the hidden pad.
        assertTrue("Run the upgrade probe in portrait", device.displayHeight > device.displayWidth)
        val center = device.wait(Until.findObject(By.descStartsWith("专注，剩余")), 10000)!!.visibleBounds
        val width = minOf(device.displayWidth - 64 * density, 320 * density)
        val left = center.exactCenterX() - width / 2
        val top = center.exactCenterY() - width / 2
        fun point(column: Int, row: Int) = Point((left + (column + 0.5f) * width / 3).toInt(), (top + (row + 0.5f) * width / 3).toInt())
        // The timer is shown immediately while a first-launch encryption migration runs.
        // Gestures during initialization are intentionally ignored; retry after the migration.
        var unlocked = false
        for (attempt in 0 until 4) {
            device.swipe(arrayOf(point(0, 0), point(1, 0), point(2, 0), point(2, 1)), 35)
            if (device.wait(Until.hasObject(By.text(peerName)), 3000)) { unlocked = true; break }
        }
        assertTrue(unlocked)
        val database = context.getDatabasePath("touch.db").readBytes()
        assertFalse(database.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
        assertFalse(database.toString(Charsets.ISO_8859_1).contains(probe.getString("user")))
        device.findObject(By.text(peerName)).click()
        assertTrue(device.wait(Until.hasObject(By.text(messageText)), 15000))
        device.pressBack()
        device.wait(Until.findObject(By.desc("设置")), 10000)!!.click()
        device.wait(Until.findObject(By.text("本机数据")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.text("本地定时销毁")), 5000))
        assertTrue(device.wait(Until.hasObject(By.textContains("已关闭，不按时间清理")), 5000))
        val toggle = device.wait(Until.findObject(By.desc("本地定时销毁开关").checkable(true).enabled(true)), 20000)!!
        assertFalse(toggle.isChecked)
        toggle.click()
        device.wait(Until.findObject(By.text("确认并开启")), 5000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.textContains("保留最近 1 小时")), 5000))
        device.wait(Until.findObject(By.text("设置保留时间")), 10000)!!.click()
        device.wait(Until.findObject(By.text("24 小时")), 10000)!!.click()
        device.wait(Until.findObject(By.text("确认并应用")), 10000)!!.click()
        assertTrue(device.wait(Until.hasObject(By.textContains("保留最近 24 小时")), 5000))
    }
}
