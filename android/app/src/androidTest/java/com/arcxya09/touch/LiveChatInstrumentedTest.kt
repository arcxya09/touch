package com.arcxya09.touch

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in network test; credentials must identify disposable acceptance accounts. */
@RunWith(AndroidJUnit4::class)
class LiveChatInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun chatAndAttachmentPreviewsOverProductionTls() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val username = args.getString("touchTestUser")
        assumeTrue(username?.startsWith("verify_") == true)
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        app.repository.logout(false)
        app.secureStore.write("privacy", null)
        File(app.filesDir, "privacy.enabled").delete()
        app.repository.login(username!!, args.getString("touchTestPassword")!!)
        app.repository.sync()
        val cid = app.repository.conversations().single().id
        app.repository.history(cid)
        val rows = app.repository.messages(cid)
        assertTrue(rows.any { it.text == "生产联调：你好，Touch" })
        for (message in rows.filter { it.file != null }) {
            val file = app.repository.download(message.file!!) { }
            assertEquals(message.file!!.size, file.length())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AppViewModel
            scenario.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(15000) { compose.onAllNodesWithText("验收设备B").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("验收设备B").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("验收文本.txt").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15000) { !model.busy }
            compose.onNodeWithText("生产联调：你好，Touch").assertIsDisplayed()
            compose.onNodeWithText("验收文本.txt").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Touch UTF-8 文件传输验证").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Touch UTF-8 文件传输验证").assertIsDisplayed()
            compose.onNodeWithText("返回").performClick()
            compose.waitUntil(15000) { !model.busy }
            compose.onNodeWithText("验收图片.png").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("文件预览").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("文件预览").assertIsDisplayed()
            compose.onNodeWithText("返回").performClick()
            compose.waitUntil(15000) { !model.busy }
            compose.onNodeWithText("验收文档.pdf").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("下一页").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("文件预览").assertIsDisplayed()
        }
        app.repository.send(cid, "Android 设备端发送验证")
        app.repository.sync()
        assertTrue(app.repository.messages(cid).any { it.text == "Android 设备端发送验证" && !it.pending })
    }
}
