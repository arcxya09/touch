package com.arcxya09.touch

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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

    @Test fun pendingMessagesOnlyShowFailureAfterExplicitRejection() {
        var sending by mutableStateOf(false)
        var delivery by mutableStateOf(PendingDelivery.Queued)
        compose.setContent { TouchTheme { PendingMessageActions(sending, false, {}, {}, delivery) } }
        compose.onNodeWithText("待发送").assertIsDisplayed()
        compose.onNodeWithText("发送失败").assertDoesNotExist()
        compose.runOnIdle { sending = true; delivery = PendingDelivery.Unconfirmed }
        compose.onNodeWithText("发送中").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsNotEnabled()
        compose.onNodeWithText("删除").assertIsNotEnabled()
        compose.runOnIdle { sending = false }
        compose.onNodeWithText("等待确认").assertIsDisplayed()
        compose.onNodeWithText("发送失败").assertDoesNotExist()
        compose.onNodeWithText("重试").assertIsEnabled()
        compose.runOnIdle { delivery = PendingDelivery.Failed }
        compose.onNodeWithText("发送失败").assertIsDisplayed()
    }

    @Test fun backCannotDismissAnAttachmentConfirmationWhileUploading() {
        var working by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var submitted = 0
        var cancelled = 0
        var dialogView: View? = null
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun pressBackOnFocusedDialog() {
            compose.onNodeWithText("selected-image.png").assertIsDisplayed()
            compose.waitUntil(10000) {
                compose.runOnUiThread { dialogView?.let { it.isAttachedToWindow && it.hasWindowFocus() } == true }
            }
            assertTrue("Back must be injected into the current focused dialog", device.pressBack())
            compose.waitForIdle()
        }
        compose.setContent { TouchTheme {
            if (visible) SelectionConfirmationDialog(false, working, true, { visible = false }, { working = true; submitted++ },
                cancel = { cancelled++; working = false; visible = false }) {
                val view = LocalView.current
                DisposableEffect(view) {
                    dialogView = view
                    onDispose { if (dialogView === view) dialogView = null }
                }
                Text("selected-image.png")
            }
        } }
        compose.onNodeWithText("发送").performClick()
        compose.onNodeWithText("取消上传").assertIsEnabled()
        compose.onNodeWithText("发送").assertIsNotEnabled()
        pressBackOnFocusedDialog()
        compose.onNodeWithText("selected-image.png").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, submitted); assertEquals(0, cancelled); assertTrue(visible) }
        compose.onNodeWithText("取消上传").performClick()
        compose.onNodeWithText("selected-image.png").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, cancelled); assertFalse(working); visible = true }
        pressBackOnFocusedDialog()
        compose.onNodeWithText("selected-image.png").assertDoesNotExist()
    }

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

    @Test fun longPressTextMenuDoesNotMoveBubbleOrFollowingStatus() {
        assertMessageMenuKeepsLayout(isText = true, quoted = false)
    }

    @Test fun longPressQuotedTextMenuDoesNotMoveBubbleOrFollowingStatus() {
        assertMessageMenuKeepsLayout(isText = true, quoted = true)
    }

    @Test fun longPressFileMenuDoesNotMoveBubbleOrFollowingStatus() {
        assertMessageMenuKeepsLayout(isText = false, quoted = false)
    }

    @Test fun ownMessageCanRecallFromLongPressMenu() {
        var recalls = 0
        compose.setContent {
            TouchTheme { MessageMenuFixture(onRecall = { recalls++ }) }
        }
        compose.onNodeWithTag("menu-message-text").performTouchInput { longClick() }
        compose.onNodeWithText("对方需更新并打开 Touch").assertDoesNotExist()
        compose.onNodeWithText("撤回消息").assertIsEnabled().performClick()
        compose.onNodeWithText("撤回消息").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, recalls) }
    }

    @Test fun busyRecallIsVisibleButCannotRunAndRequiresNoPeerUpdate() {
        var recalls = 0
        compose.setContent {
            TouchTheme { MessageMenuFixture(busy = true, onRecall = { recalls++ }) }
        }
        compose.onNodeWithTag("menu-message-text").performTouchInput { longClick() }
        val action = compose.onNodeWithText("撤回消息")
        action.assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("对方需更新并打开 Touch").assertDoesNotExist()
        action.performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, recalls) }
        action.assertIsDisplayed()
    }

    @Test fun peerPendingAndRecalledMessageMenusDoNotOfferRecall() {
        data class Case(val own: Boolean, val valid: Boolean, val recalled: Boolean)
        val cases = listOf(Case(false, true, false), Case(true, false, false), Case(true, true, true))
        var selected by mutableStateOf(cases.first())
        compose.setContent {
            TouchTheme {
                key(selected) {
                    MessageMenuFixture(own = selected.own, valid = selected.valid, recalled = selected.recalled,
                        initiallyExpanded = true)
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { selected = case }
            compose.onNodeWithText("本地删除").assertExists()
            compose.onNodeWithText("撤回消息").assertDoesNotExist()
            compose.onNodeWithText("对方需更新并打开 Touch").assertDoesNotExist()
        }
    }

    private fun assertMessageMenuKeepsLayout(isText: Boolean, quoted: Boolean) {
        compose.setContent {
            TouchTheme { MessageMenuFixture(isText = isText, quoted = quoted) }
        }
        val bubble = compose.onNodeWithTag("menu-message-bubble", useUnmergedTree = true)
        val status = compose.onNodeWithTag("menu-message-status", useUnmergedTree = true)
        val initialBubble = bubble.fetchSemanticsNode().boundsInRoot
        val initialStatus = status.fetchSemanticsNode().boundsInRoot
        val target = if (isText) compose.onNodeWithTag("menu-message-text") else bubble
        target.performTouchInput { longClick() }
        compose.onNodeWithText("本地删除").assertIsDisplayed()
        assertEquals("Opening the message menu must preserve the complete bubble bounds",
            initialBubble, bubble.fetchSemanticsNode().boundsInRoot)
        assertEquals("Opening the message menu must not move the following status row",
            initialStatus, status.fetchSemanticsNode().boundsInRoot)
        Espresso.pressBack()
        compose.onNodeWithText("本地删除").assertDoesNotExist()
        assertEquals("Closing the message menu must preserve the complete bubble bounds",
            initialBubble, bubble.fetchSemanticsNode().boundsInRoot)
        assertEquals("Closing the message menu must not move the following status row",
            initialStatus, status.fetchSemanticsNode().boundsInRoot)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable private fun MessageMenuFixture(isText: Boolean = true, quoted: Boolean = false,
        own: Boolean = true, valid: Boolean = true, recalled: Boolean = false, busy: Boolean = false,
        initiallyExpanded: Boolean = false, onRecall: () -> Unit = {}) {
        var expanded by remember { mutableStateOf(initiallyExpanded) }
        val showMenu = { if (valid) expanded = true; Unit }
        val dismiss = { expanded = false }
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            MessageBubbleFrame(own, modifier = Modifier.widthIn(max = 260.dp).testTag("menu-message-bubble")
                .then(if (!isText) Modifier.combinedClickable(onClick = {}, onLongClick = showMenu) else Modifier),
                menu = {
                    MessageActionsMenu(expanded, dismiss, valid, recalled, isText, own, busy,
                        onQuote = dismiss, onCopy = dismiss, onSelect = dismiss, onDelete = dismiss,
                        onRecall = { onRecall(); expanded = false })
                }) {
                if (quoted) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.padding(8.dp)) {
                        Text("引用小林的消息", style = MaterialTheme.typography.labelSmall)
                        Text("引用内容\n第二行引用内容", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (isText) MessageText("这是一条可长按的消息\n第二行消息内容", {},
                    Modifier.testTag("menu-message-text"), onLongPress = showMenu)
                else {
                    Icon(Icons.Outlined.Description, null, Modifier.padding(bottom = 8.dp))
                    Text("季度总结.pdf", style = MaterialTheme.typography.titleSmall)
                    SupportingNote("2.4 MiB · 点击查看")
                }
            }
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp).testTag("menu-message-status"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("12:34 · 已发送", style = MaterialTheme.typography.labelSmall)
            }
        }
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
