package com.arcxya09.touch

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class NotificationNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun coldAndWarmMessageIntentsRespectPrivacyAndOpenTheConversation() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("touchTestUser")?.startsWith("verify_local_") == true)
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        app.repository.initialize()
        app.repository.logout(false)
        app.repository.login(args.getString("touchTestUser")!!, args.getString("touchTestPassword")!!)
        app.repository.sync()
        val cid = app.repository.conversations().first().id
        app.secureStore.write("privacy", JSONObject().put("enabled", false).toString())
        File(app.filesDir, "privacy.enabled").delete()
        fun messageIntent() = Intent(app, MainActivity::class.java)
            .putExtra("message_alert", true).putExtra("conversation_id", cid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ActivityScenario.launch<MainActivity>(messageIntent()).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(15000) { vm.mayShowChat && vm.screen == "chat" && !vm.busy }
            assertEquals(cid, vm.conversationId)
            compose.onNodeWithContentDescription("添加图片或文件").performClick()
            compose.onNodeWithText("添加内容").assertIsDisplayed()
            compose.onNodeWithText("图片").assertIsDisplayed()
            compose.onNodeWithText("文件").assertIsDisplayed()
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            scenario.onActivity { vm.screen = "timer"; it.startActivity(messageIntent()) }
            compose.waitUntil(15000) { vm.mayShowChat && vm.screen == "chat" }
            assertEquals(cid, vm.conversationId)
        }
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", "0125").toString())
        File(app.filesDir, "privacy.enabled").writeText("1")
        ActivityScenario.launch<MainActivity>(messageIntent()).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(15000) { vm.initialized }
            assertTrue(vm.locked)
            assertFalse(vm.mayShowChat)
            compose.onNodeWithTag("timer-screen").assertIsDisplayed()
            scenario.onActivity { vm.unlock(listOf(0, 1, 2, 5)) }
            compose.waitUntil(15000) { vm.mayShowChat && vm.screen == "chat" }
            assertEquals(cid, vm.conversationId)
        }
    }
}
