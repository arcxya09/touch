package com.arcxya09.touch

import androidx.compose.ui.geometry.Offset
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

/** Opt-in integration against disposable accounts only. */
class ConversationOnboardingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun gesture(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(Offset(width / 6f, height / 6f))
            moveTo(Offset(width / 2f, height / 6f), 100)
            moveTo(Offset(width * 5f / 6f, height / 6f), 100)
            moveTo(Offset(width * 5f / 6f, height / 2f), 100)
            up()
        }
    }
    @Test fun firstLoginChoicePersistsAndDeletedHistoryDoesNotReturn() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("touchTestUser")
        assumeTrue(name?.startsWith("verify_") == true)
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val repo = app.repository
        repo.logout(false)
        app.secureStore.write("privacy", null)
        File(app.filesDir, "privacy.enabled").delete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            fun model() { scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] } }
            model()
            compose.waitUntil(10000) { vm.initialized && vm.mayShowSession }
            scenario.onActivity { vm.login(name!!, args.getString("touchTestPassword")!!) }
            compose.waitUntil(15000) { vm.user != null && !vm.busy }
            assertTrue(vm.needsPrivacySetup)
            assertFalse(vm.mayShowChat)
            compose.onNodeWithText("绘制图案").assertIsDisplayed()
            compose.onNodeWithText("消息").assertDoesNotExist()
            scenario.recreate(); model()
            compose.waitUntil(10000) { vm.initialized && vm.mayShowSession }
            assertTrue(vm.needsPrivacySetup)
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("跳过设置，使用正常模式").performClick()
            compose.waitUntil(15000) { vm.mayShowChat && !vm.busy && vm.conversations.isNotEmpty() }
            assertFalse(vm.privacy)
            scenario.recreate(); model()
            compose.waitUntil(10000) { vm.mayShowChat }
            assertFalse(vm.needsPrivacySetup)
            val cid = repo.conversations().first().id
            scenario.onActivity { vm.openConversation(cid) }
            compose.waitUntil(10000) { !vm.busy && vm.screen == "chat" }
            scenario.onActivity { vm.send("delete-fixture") {} }
            compose.waitUntil(15000) { !vm.busy && vm.messages.any { it.text == "delete-fixture" } }
            val source = File(app.cacheDir, "delete-fixture.txt").apply { writeText("encrypted attachment") }
            val item = repo.upload(android.net.Uri.fromFile(source), "file") {}
            source.delete()
            repo.send(cid, attachment = item)
            repo.sync()
            val handle = repo.download(repo.messages(cid).first { it.file?.id == item.id }) {}
            assertTrue(handle.valid())
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("删除会话").performClick()
            compose.onNodeWithText("确认").performClick()
            compose.waitUntil(15000) { !vm.busy && vm.screen == "home" }
            assertTrue(repo.messages(cid).isEmpty())
            assertFalse(handle.valid())
            assertFalse(File(app.cacheDir, "sealed-attachments/${item.id}").exists())
            assertTrue(repo.conversations().isEmpty())
            repo.sync()
            assertTrue(repo.conversations().isEmpty())
            assertTrue(repo.messages(cid).isEmpty())
            scenario.onActivity { vm.openConversation(cid) }
            compose.waitUntil(10000) { !vm.busy && vm.screen == "chat" }
            assertTrue(vm.messages.isEmpty())
            scenario.onActivity { vm.send("new-after-delete") {} }
            compose.waitUntil(15000) { !vm.busy && vm.messages.any { it.text == "new-after-delete" } }
            assertEquals(listOf("new-after-delete"), repo.messages(cid).map { it.text })
        }
        // A persisted incomplete choice must still require setup after process recreation.
        app.secureStore.write("privacy", JSONObject().put("pending", true).toString())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(10000) { vm.initialized && vm.mayShowSession }
            gesture("setup-pattern")
            compose.onNodeWithText("再次确认").assertIsDisplayed()
            gesture("setup-pattern")
            compose.onNodeWithText("隐藏图案试解锁").assertIsDisplayed()
            gesture("setup-pattern")
            compose.waitUntil(10000) { vm.privacy && vm.locked && !vm.busy }
            compose.onNodeWithTag("timer-screen").assertIsDisplayed()
            scenario.recreate()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("hidden-pattern").fetchSemanticsNodes().isNotEmpty() }
            gesture("hidden-pattern")
            compose.waitUntil(10000) { compose.onAllNodesWithText("消息").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
