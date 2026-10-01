package com.arcxya09.touch

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.SensorEvent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.arcxya09.touch.security.SafetyOptions
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SafetyPreviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<TouchApp>()
    private suspend fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("touchTestUser")?.startsWith("verify_local_") == true)
        app.repository.initialize(); app.repository.logout(false)
        app.repository.login(args.getString("touchTestUser")!!, args.getString("touchTestPassword")!!)
        app.repository.enableRetention(false); app.repository.sync()
        app.secureStore.write("privacy", JSONObject().put("enabled", false).toString())
        File(app.filesDir, "privacy.enabled").delete()
        SafetyOptions().save(app.vault)
    }
    @Test fun encryptedImageThumbnailAndRecentTaskToggle() = runBlocking<Unit> {
        prepare()
        val repo = app.repository
        val source = File(app.cacheDir, "thumbnail-fixture.png")
        try {
            source.outputStream().use { out -> Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, out) }
            val bytes = source.readBytes()
            val item = repo.upload(Uri.fromFile(source), "image") {}
            val cid = repo.conversations().single().id
            repo.send(cid, attachment = item); repo.sync()
            val message = repo.messages(cid).last { it.file?.id == item.id }
            val results = coroutineScope { List(3) { async { repo.download(message) {} } }.awaitAll() }
            results.forEach { assertArrayEquals(bytes, it.input().use { input -> input.readBytes() }) }
            assertFalse(results.first().encryptedFile.readBytes().contentEquals(bytes))
            assertFalse(results.first().encryptedFile.parentFile!!.listFiles()!!.any { it.extension == "part" })
            source.delete()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppViewModel
                var task = 0
                scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; task = it.taskId }
                compose.waitUntil(15000) { vm.connected }
                val title = compose.onNodeWithText("消息").fetchSemanticsNode().boundsInRoot
                val status = compose.onNodeWithTag("connection-status").fetchSemanticsNode().boundsInRoot
                assertTrue(status.top >= title.bottom)
                compose.onNodeWithTag("connection-status").assertHasClickAction()
                scenario.onActivity { vm.openConversation(cid) }
                compose.waitUntil(20000) { compose.onAllNodesWithTag("thumbnail-${message.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("thumbnail-${message.id}", useUnmergedTree = true).assertIsDisplayed()
                scenario.onActivity { vm.navigate(Screen.Settings) }
                compose.onNodeWithText("隐私与安全").performScrollTo().performClick()
                compose.onNodeWithContentDescription("从最近任务隐藏开关").performScrollTo().assertIsOff().performClick()
                compose.waitUntil(5000) { vm.safety.hideRecents && !vm.busy }
                compose.waitForIdle()
                fun hidden() = app.getSystemService(ActivityManager::class.java).appTasks.first { it.taskInfo?.taskId == task }.taskInfo!!.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0
                assertTrue(hidden())
                scenario.recreate()
                scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
                compose.waitUntil(15000) { vm.initialized }
                assertTrue(vm.safety.hideRecents); assertTrue(hidden())
                scenario.onActivity { vm.navigate(Screen.Settings) }
                compose.onNodeWithText("隐私与安全").performScrollTo().performClick()
                compose.onNodeWithContentDescription("从最近任务隐藏开关").performScrollTo().performClick()
                compose.waitUntil(5000) { !vm.safety.hideRecents && !vm.busy }
                compose.waitForIdle(); assertFalse(hidden())
                assertFalse(vm.safety.flipExit); assertFalse(vm.safety.shakeExit)
            }
        } finally { source.delete(); SafetyOptions().save(app.vault) }
    }
    @Test fun motionExitRemovesTaskAndReopensPrivacyLocked() = runBlocking<Unit> {
        prepare()
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", "0125").toString())
        File(app.filesDir, "privacy.enabled").writeText("1")
        SafetyOptions(flipExit = true).save(app.vault)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppViewModel
                var task = 0
                scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java]; task = it.taskId }
                compose.waitUntil(15000) { vm.initialized }
                scenario.onActivity { vm.unlock(listOf(0,1,2,5)) }
                compose.waitUntil(15000) { vm.mayShowChat }
                compose.waitForIdle()
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("detector").apply { isAccessible = true }.set(activity, com.arcxya09.touch.security.MotionExitDetector())
                    val constructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
                    fun sample(ms: Long, z: Float) {
                        val event = constructor.newInstance(3)
                        event.values[2] = z; event.timestamp = ms * 1000000
                        activity.onSensorChanged(event)
                    }
                    sample(1000000, 9.8f); sample(1000700, -9.8f); sample(1001300, -9.8f)
                }
                compose.waitUntil(10000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                assertTrue(vm.locked); assertFalse(vm.mayShowChat)
                assertFalse(app.getSystemService(ActivityManager::class.java).appTasks.any { it.taskInfo?.taskId == task })
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
                compose.waitUntil(15000) { vm.initialized }
                assertTrue(vm.locked); assertFalse(vm.mayShowChat)
                compose.onNodeWithText("消息").assertDoesNotExist()
            }
        } finally { SafetyOptions().save(app.vault) }
    }
}
