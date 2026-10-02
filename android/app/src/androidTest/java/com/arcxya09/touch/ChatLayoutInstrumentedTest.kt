package com.arcxya09.touch

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.arcxya09.touch.ui.*
import com.arcxya09.touch.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File

class ChatLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var previousKeyboardSetting: String? = null
    @Before fun allowSoftwareKeyboardOnHardwareKeyboardEmulators() {
        val previous = shell("settings get secure show_ime_with_hard_keyboard")
        check(previous in setOf("null", "0", "1")) { "Unexpected emulator keyboard setting" }
        previousKeyboardSetting = previous
        shell("settings put secure show_ime_with_hard_keyboard 1")
    }
    @After fun restoreKeyboardSetting() {
        previousKeyboardSetting?.let {
            shell(if (it == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $it")
        }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    @Test fun headerTextGroupAlignsWithActionsAndAttachmentSitsInsideInput() {
        var attachments = 0
        var diagnostics = 0
        var draft by mutableStateOf("")
        compose.setContent {
            TouchTheme {
                Column {
                    TouchHeader("消息", status = "已连接", onStatus = { diagnostics++ }) {
                        IconButton(onClick = {}, modifier = Modifier.testTag("header-action")) {
                            Icon(Icons.Outlined.Settings, "设置")
                        }
                    }
                    ChatComposer(draft, false, true, { draft = it }, {}, { attachments++ })
                }
            }
        }
        val title = compose.onNodeWithText("消息", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("connection-status", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("header-action").fetchSemanticsNode().boundsInRoot
        assertEquals(action.center.y, (title.top + status.bottom) / 2f, 2f)
        compose.onNodeWithText("消息").performClick()
        compose.runOnIdle { assertEquals(1, diagnostics) }
        val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val attachment = compose.onNodeWithContentDescription("添加图片或文件").fetchSemanticsNode().boundsInRoot
        assertTrue(attachment.left >= input.left && attachment.right <= input.right)
        assertTrue(attachment.top >= input.top && attachment.bottom <= input.bottom)
        assertEquals(input.center.y, compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot.center.y, 2f)
        compose.onNodeWithContentDescription("添加图片或文件").performClick()
        compose.runOnIdle { assertEquals(1, attachments) }
        compose.runOnIdle { draft = "第一行\n第二行\n第三行" }
        val multiline = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertEquals(multiline.center.y, compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot.center.y, 2f)
    }

    @Test fun conversationTimeIsCenteredAcrossBothTextLines() {
        val timestamp = java.time.LocalDate.now().atTime(12, 34).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        val message = ChatMessage("last", "conversation", "peer", "client", 10, "text", "最新消息", timestamp, null)
        compose.setContent {
            TouchTheme {
                ConversationListContent(listOf(Conversation("conversation", Person("peer", "peer", "小林"), 3, 0, true, message)),
                    true, ConnectionStatus.LIVE, false, false, avatar = { Spacer(Modifier.size(52.dp)) },
                    onOpen = {}, onContacts = {}, onSettings = {}, onHide = {}, onDiagnostics = {}, onRetry = {}, onDelete = {})
            }
        }
        val time = compose.onNodeWithText("12:34", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithContentDescription("与小林的会话操作").fetchSemanticsNode().boundsInRoot
        assertEquals(menu.center.y, time.center.y, 2f)
    }

    @Test fun scrollingBackToBottomHidesJumpUnlessNewerMessagesAreOutsidePage() {
        lateinit var scroll: LazyListState
        lateinit var scope: CoroutineScope
        var newer by mutableStateOf(false)
        compose.setContent {
            scroll = rememberLazyListState()
            scope = rememberCoroutineScope()
            val bottom by rememberChatAtBottom(scroll, browsingHistory = true)
            TouchTheme {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = scroll) { items(60) { Text("历史 $it", Modifier.height(64.dp)) } }
                    JumpToLatest(bottom, newer, false, onClick = {})
                }
            }
        }
        compose.onNodeWithTag("jump-to-latest").assertExists()
        compose.runOnIdle { scope.launch { scroll.scrollToItem(59) } }
        compose.waitForIdle()
        compose.onNodeWithTag("jump-to-latest").assertDoesNotExist()
        compose.runOnIdle { newer = true }
        compose.onNodeWithTag("jump-to-latest").assertExists()
        compose.runOnIdle { newer = false; scope.launch { scroll.scrollToItem(5) } }
        compose.waitForIdle()
        compose.onNodeWithTag("jump-to-latest").assertExists()
    }

    @Test fun keyboardMovesLatestMessageAndPreservesHistoryPosition() {
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        lateinit var scroll: LazyListState
        lateinit var scope: CoroutineScope
        var keyboardBottom = 0
        compose.setContent {
            scroll = rememberLazyListState(initialFirstVisibleItemIndex = 41)
            scope = rememberCoroutineScope()
            rememberChatAtBottom(scroll, browsingHistory = false)
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { keyboardBottom = bottom }
            var draft by remember { mutableStateOf("") }
            TouchTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                        ChatHeader("小林", {}, {}) {}
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("messages"), state = scroll) {
                            items(40) { Text("历史消息 $it", Modifier.fillMaxWidth().height(64.dp).padding(16.dp)) }
                            item { Text("最新消息", Modifier.fillMaxWidth().padding(16.dp).testTag("latest")) }
                            item { Spacer(Modifier.height(1.dp)) }
                        }
                        ChatComposer(draft, false, true, { draft = it }, {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(10000) { compose.activity.hasWindowFocus() }
        val originalTop = compose.onNodeWithTag("latest").fetchSemanticsNode().boundsInRoot.top
        saveScreenshot("chat-keyboard-hidden")
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(10000) { keyboardBottom > 0 }
        compose.waitForIdle()
        compose.onNodeWithTag("latest").assertIsDisplayed()
        val raised = compose.onNodeWithTag("latest").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("messages").fetchSemanticsNode().boundsInRoot
        assertTrue("Latest message must move with the keyboard", raised.top < originalTop - 100)
        assertTrue("Latest message must remain above the composer", raised.bottom <= viewport.bottom + 1)
        compose.runOnIdle { assertFalse(scroll.canScrollForward) }
        saveScreenshot("chat-keyboard-visible")
        Espresso.pressBack()
        compose.waitUntil(10000) { keyboardBottom == 0 }
        compose.waitForIdle()
        assertEquals(originalTop, compose.onNodeWithTag("latest").fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.runOnIdle { scope.launch { scroll.scrollToItem(5, 12) } }
        compose.waitForIdle()
        val historyIndex = scroll.firstVisibleItemIndex
        val historyOffset = scroll.firstVisibleItemScrollOffset
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(10000) { keyboardBottom > 0 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(historyIndex, scroll.firstVisibleItemIndex)
            assertEquals(historyOffset, scroll.firstVisibleItemScrollOffset)
            assertTrue(scroll.canScrollForward)
        }
    }

    private fun saveScreenshot(name: String) {
        val folder = File(compose.activity.filesDir, "chat-layout-snapshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
