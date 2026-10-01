package com.arcxya09.touch

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.arcxya09.touch.notifications.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class AlertInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<TouchApp>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun message() = manager.activeNotifications.firstOrNull { it.id == AlertNotifications.MESSAGE_ID }?.notification
    private fun body() = message()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private suspend fun await(timeout: Long = 15000, predicate: () -> Boolean) {
        withTimeout(timeout) { while (!predicate()) delay(100) }
    }

    @Test fun encryptedOptInIsAccountBoundAndAlwaysResetsToDiscreet() = runBlocking<Unit> {
        app.alertSettings.disable()
        assertFalse(app.alertSettings.read().enabled)
        assertEquals(AlertMode.DISCREET, app.alertSettings.read().mode)
        app.alertSettings.enable("test-owner")
        app.alertSettings.mode(AlertMode.CONTENT)
        assertFalse(app.alertSettings.read().canRun("other-owner"))
        val restored = AlertSettings(app.vault).read()
        assertEquals(AlertMode.CONTENT, restored.mode)
        val encrypted = File(app.noBackupFilesDir, "vault/alerts").readBytes().toString(Charsets.UTF_8)
        assertFalse(encrypted.contains("test-owner")); assertFalse(encrypted.contains("CONTENT"))
        app.alertSettings.pause(true); assertFalse(app.alertSettings.read().canRun("test-owner"))
        app.alertSettings.disable(); app.alertSettings.enable("test-owner")
        assertEquals(AlertMode.DISCREET, app.alertSettings.read().mode)
        assertTrue(app.alertSettings.read().canRun("test-owner"))
        app.alertSettings.disable()
    }

    @Test fun backgroundReceivePrivacyPauseAndRevocation() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("touchTestUser")?.startsWith("verify_local_") == true)
        val repo = app.repository
        repo.initialize(); repo.logout(false)
        repo.login(args.getString("touchTestUser")!!, args.getString("touchTestPassword")!!)
        repo.sync()
        val owner = repo.api.user!!.id
        val cid = repo.conversations().first().id
        val client = OkHttpClient()
        fun request(path: String, json: JSONObject, token: String? = null): JSONObject {
            val request = Request.Builder().url(BuildConfig.API_BASE + path)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
            return client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Fixture HTTP ${response.code}" }; JSONObject(response.body!!.string())
            }
        }
        val peer = request("/api/v1/auth/login", JSONObject().put("username", args.getString("touchPeerUser")).put("password", args.getString("touchPeerPassword")))
        fun send(text: String) = request("/api/v1/conversations/$cid/messages", JSONObject().put("client_id", UUID.randomUUID().toString())
            .put("kind", "text").put("text", text), peer.getString("access_token"))
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", "0125").toString())
        File(app.filesDir, "privacy.enabled").writeText("1")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        lateinit var vm: AppViewModel
        var recoveryPendingId: String? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(15000) { vm.initialized && vm.privacy }
            scenario.onActivity { vm.unlock(listOf(0, 1, 2, 5)) }
            compose.waitUntil(15000) { vm.mayShowChat && !vm.busy }
            assertFalse(vm.alertOptions.enabled)
            send("old-before-opt-in")
            scenario.onActivity { vm.enableAlerts(true) }
            compose.waitUntil(15000) { vm.alertOptions.enabled && !vm.busy && AlertService.running }
            assertEquals(AlertMode.DISCREET, vm.alertOptions.mode)
            await { AlertService.connected }
            assertNull(message())
            send("foreground-suppressed")
            delay(3500); assertNull(message())
            device.pressHome()
            await { vm.locked && !vm.mayShowChat }
            send("private-message-one")
            await { message() != null }
            assertFalse(body()!!.contains("private-message-one"))
            assertTrue(body()!!.contains("休息"))
            val postTime = manager.activeNotifications.first { it.id == AlertNotifications.MESSAGE_ID }.postTime
            repo.sync(); delay(500)
            assertEquals(postTime, manager.activeNotifications.first { it.id == AlertNotifications.MESSAGE_ID }.postTime)
            device.openNotification()
            val notice = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("已经专注一段时间了，记得休息一下。")), 10000)
            assertNotNull(notice)
            notice.click()
            compose.waitUntil(10000) { vm.locked && vm.screen == "timer" && compose.onAllNodesWithTag("timer-screen").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(vm.mayShowChat)
            scenario.onActivity { vm.screen = "home"; vm.unlock(listOf(0, 1, 2, 5)); vm.alertMode(AlertMode.CONTENT) }
            compose.waitUntil(10000) { !vm.busy && vm.alertOptions.mode == AlertMode.CONTENT }
            device.pressHome(); await { vm.locked }
            send("real-message-two")
            await { body() == "real-message-two" }
            assertEquals(Notification.VISIBILITY_SECRET, message()!!.visibility)
            device.sleep()
            await { body()?.contains("休息") == true }
            device.wakeUp(); device.pressMenu()
            // Destroying the activity must not destroy the independent receiver service.
            scenario.onActivity { it.finishAndRemoveTask() }
            scenario.close()
            assertTrue(AlertService.running)
            val retainedMessage = send("activity-destroyed-message")
            await { repo.api.user != null && message() != null }
            withTimeout(15000) {
                while (repo.messages(cid).none { it.id == retainedMessage.getString("id") }) delay(100)
            }
            delay(2500)
            assertTrue(AlertService.running)
            app.sendBroadcast(Intent(app, AlertStopReceiver::class.java))
            await { !AlertService.running }
            assertTrue(app.alertSettings.read().paused); assertNull(message())
            send("while-paused"); delay(1500); assertNull(message())
            val tile = "${app.packageName}/com.arcxya09.touch.notifications.ReminderTileService"
            device.executeShellCommand("cmd statusbar add-tile $tile")
            device.openQuickSettings(); delay(1500)
            device.executeShellCommand("cmd statusbar click-tile $tile")
            await { AlertService.running && !app.alertSettings.read().paused }
            device.executeShellCommand("cmd statusbar click-tile $tile")
            await { !AlertService.running && app.alertSettings.read().paused }
            device.executeShellCommand("cmd statusbar collapse")
            // Interrupt after the real durable queue write, before sending. Background sync
            // must not submit this pending item, and revocation must not erase it.
            val pendingText = "revocation-recovery-${UUID.randomUUID()}"
            val queueResult = runCatching {
                repo.send(cid, text = pendingText, queued = { throw CancellationException("Keep recovery fixture pending") })
            }
            assertTrue(queueResult.exceptionOrNull() is CancellationException)
            val pendingId = repo.messages(cid).single { it.pending && it.text == pendingText }.id
            recoveryPendingId = pendingId
            val pendingBody = withContext(Dispatchers.IO) { repo.db.cache().pendingItems().single { it.id == pendingId }.body }
            // A user-visible activity makes starting a foreground service legitimate.
            ActivityScenario.launch(MainActivity::class.java).use {
                withContext(Dispatchers.IO) { app.alertSettings.pause(false) }
                withContext(Dispatchers.Main) { assertTrue(AlertService.start(app)) }
                await { AlertService.running && AlertService.connected }
                device.pressHome()
                request("/api/v1/auth/login", JSONObject().put("username", args.getString("touchTestUser")).put("password", args.getString("touchTestPassword")))
                await(45000) { repo.api.user == null && !AlertService.running }
                assertFalse(app.alertSettings.read().enabled)
                assertNull(message())
                assertNull(repo.api.session)
                assertNull(app.secureStore.read("session"))
                assertTrue(repo.messages(cid).any { it.id == retainedMessage.getString("id") && it.text == "activity-destroyed-message" })
                withContext(Dispatchers.IO) {
                    assertEquals(owner, repo.db.cache().get("meta", "owner")?.json)
                    assertEquals(pendingBody, repo.db.cache().pendingItems().single { it.id == pendingId }.body)
                }
            }
            // Encrypted content remains recoverable, but reopening and unlocking must
            // expose only the signed-out UI until the original account authenticates.
            ActivityScenario.launch(MainActivity::class.java).use { recoveryScenario ->
                lateinit var recovered: AppViewModel
                recoveryScenario.onActivity { recovered = ViewModelProvider(it)[AppViewModel::class.java] }
                compose.waitUntil(15000) { recovered.initialized && recovered.gate == SessionGate.Locked }
                assertFalse(recovered.mayShowChat)
                assertNull(recovered.user)
                assertTrue(recovered.messages.isEmpty())
                assertTrue(recovered.conversations.isEmpty())
                compose.onNodeWithText("activity-destroyed-message").assertDoesNotExist()
                compose.onNodeWithText(pendingText).assertDoesNotExist()
                recoveryScenario.onActivity { recovered.unlock(listOf(0, 1, 2, 5)) }
                compose.waitUntil(15000) { recovered.gate == SessionGate.SignedOut }
                compose.onNodeWithText("欢迎回来").assertIsDisplayed()
                compose.onNodeWithText("activity-destroyed-message").assertDoesNotExist()
                compose.onNodeWithText(pendingText).assertDoesNotExist()
                assertTrue(recovered.messages.isEmpty())
                assertTrue(recovered.conversations.isEmpty())
                recoveryScenario.onActivity { recovered.login(args.getString("touchTestUser")!!, args.getString("touchTestPassword")!!) }
                compose.waitUntil(15000) { recovered.gate == SessionGate.Ready && recovered.user?.id == owner && !recovered.busy }
                recoveryScenario.onActivity { recovered.openConversation(cid) }
                compose.waitUntil(15000) {
                    recovered.messages.any { it.id == retainedMessage.getString("id") && it.text == "activity-destroyed-message" } &&
                        recovered.messages.any { it.id == pendingId && it.pending && it.text == pendingText }
                }
                assertEquals(owner, repo.api.user?.id)
                assertFalse(app.alertSettings.read().enabled)
                assertFalse(AlertService.running)
                withContext(Dispatchers.IO) {
                    assertEquals(pendingBody, repo.db.cache().pendingItems().single { it.id == pendingId }.body)
                }
            }
        } finally {
            app.alertSettings.disable()
            withContext(Dispatchers.Main) { AlertService.stop(app) }
            recoveryPendingId?.let { repo.discard(it) }
            runCatching { scenario.close() }
            client.dispatcher.executorService.shutdown()
        }
    }
}
