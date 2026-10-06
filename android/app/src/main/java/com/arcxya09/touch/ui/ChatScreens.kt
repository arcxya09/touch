package com.arcxya09.touch.ui

import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.Operation
import com.arcxya09.touch.Screen
import com.arcxya09.touch.data.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun HomeScreen(vm: AppViewModel) {
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    ConversationListContent(vm.conversations, vm.conversationsLoaded, vm.connectionStatus,
        vm.isWorking(Operation.Conversation), vm.isWorking(Operation.Send),
        avatar = { Avatar(it, vm, 52.dp) }, onOpen = vm::openConversation,
        onContacts = { vm.navigate(Screen.Contacts) }, onSettings = { vm.navigate(Screen.Settings) },
        onHide = vm::hide, onDiagnostics = vm::diagnostics, onRetry = vm::refreshConversations,
        onDelete = { deleting = it })
    deleting?.let { item -> DeleteConversationConfirm({ deleting = null }) { vm.deleteConversation(item.id); deleting = null } }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChatScreen(vm: AppViewModel, activity: MainActivity) {
    val conversation = vm.conversations.firstOrNull { it.id == vm.conversationId }
    val peer = conversation?.peer ?: vm.contacts.firstOrNull { it.conversationId == vm.conversationId }?.peer
    // Secret text deliberately lives only in VM memory or the opt-in encrypted draft store.
    val draft = vm.draftText
    var attachments by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    val lastId = vm.messages.lastOrNull()?.id
    val atBottom by rememberChatAtBottom(scroll, vm.browsingHistory)
    val loading = vm.isWorking(Operation.Conversation)
    val sending = vm.isWorking(Operation.Send) || vm.isWorking(Operation.Attachment) || vm.isWorking(Operation.Session)
    LaunchedEffect(dragging, atBottom) { if (dragging && !atBottom) vm.holdHistory() }
    val hasIncoming = vm.hasNewerMessages
    LaunchedEffect(atBottom, hasIncoming, loading, vm.browsingHistory) {
        if (atBottom && !hasIncoming && !loading) vm.reachedLatest()
    }
    LaunchedEffect(lastId) {
        if (vm.messages.isNotEmpty()) {
            if (atBottom && !vm.browsingHistory) scroll.animateScrollToItem(vm.messages.size + 1)
        }
    }
    LaunchedEffect(vm.scrollRequest) {
        if (vm.messages.isNotEmpty()) {
            val target = vm.highlightId?.let { id -> vm.messages.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            scroll.scrollToItem(target?.plus(1) ?: if (vm.browsingHistory) 1 else vm.messages.size + 1)
        }
    }
    LaunchedEffect(vm.conversationId) {
        snapshotFlow {
            val visible = scroll.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
            vm.messages.filter { !it.pending && it.id in visible }.maxOfOrNull { it.seq } ?: 0L
        }.distinctUntilChanged().collect { vm.markVisibleRead(it) }
    }
    Column(Modifier.fillMaxSize()) {
        ChatHeader(peer?.name ?: "聊天", vm::back, vm::hide) {
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("隐藏聊天") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) }, onClick = { more = false; vm.hide() })
                    DropdownMenuItem(text = { Text("连接诊断") }, onClick = { more = false; vm.diagnostics() })
                    DropdownMenuItem(text = { Text("删除会话") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, enabled = !loading, onClick = { more = false; clear = true })
                }
            }
        }
        BusyIndicator(loading)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { if (vm.hasMore) TextButton(onClick = vm::older, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "正在加载…" else "加载更早消息") } }
                itemsIndexed(vm.messages, key = { _, message -> message.id }) { index, message ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (index == 0 || dayKey(message.createdAt) != dayKey(vm.messages[index - 1].createdAt)) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(dayLabel(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        MessageBubble(message, vm, activity::openWebLink)
                    }
                }
                item(key = "chat-bottom") { Spacer(Modifier.height(1.dp)) }
            }
            if (vm.messages.isEmpty() && !loading) EmptyState("对话从这里开始", "发一条消息，分享此刻。", modifier = Modifier.align(Alignment.Center))
            JumpToLatest(atBottom, hasIncoming, loading, Modifier.align(Alignment.BottomEnd), vm::latest)
        }
        vm.transfer?.let { progress ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("正在传输 · ${(progress * 100).toInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = vm::cancelTransfer) { Text("取消传输") }
                }
            }
        }
        ChatComposer(draft, sending, conversation?.canSend != false, vm::editDraft,
            { vm.send(draft) {} }, { attachments = true }) {
                vm.quote?.let { ref ->
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { QuotePreview(ref, vm.conversationId.orEmpty(), vm, clickable = false) }
                        IconButton(onClick = vm::cancelQuote) { Icon(Icons.Outlined.Close, "取消引用") }
                    }
                }
        }
    }
    if (attachments) ModalBottomSheet(onDismissRequest = { attachments = false }) {
        Text("添加内容", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
        SettingItem("图片", "选择要发送的图片", Icons.Outlined.Image, enabled = !sending) { attachments = false; activity.chooseImage() }
        SettingItem("文件", "选择文档或其他附件", Icons.Outlined.AttachFile, enabled = !sending) { attachments = false; activity.chooseDocument() }
        Spacer(Modifier.height(24.dp))
    }
    if (clear) DeleteConversationConfirm({ clear = false }) { vm.conversationId?.let(vm::deleteConversation); clear = false }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun MessageBubble(message: ChatMessage, vm: AppViewModel, openLink: (String) -> Unit) {
    val own = message.senderId == vm.user?.id
    var menu by remember(message.id) { mutableStateOf(false) }
    var delete by remember(message.id) { mutableStateOf(false) }
    var recall by remember(message.id) { mutableStateOf(false) }
    var selecting by remember(message.id) { mutableStateOf(false) }
    val valid = !message.pending && message.createdAt > vm.visibilityFloor
    val recalled = message.kind == "recalled"
    val canRecall = own && !recalled && vm.conversations.firstOrNull { it.id == message.conversationId }?.canRecall == true
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val busy = vm.isWorking(Operation.Send)
    val copy = {
        scope.launch {
            val clip = android.content.ClipData.newPlainText("", message.text)
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            clipboard.setClipEntry(ClipEntry(clip))
        }
        Unit
    }
    val showMenu = { if (valid) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }; Unit }
    val accessibleActions = buildList {
        if (valid) add(CustomAccessibilityAction("消息操作") { menu = true; true })
        if (valid && !recalled) add(CustomAccessibilityAction("引用回复") { vm.quoteMessage(message); true })
        if (message.kind == "text") add(CustomAccessibilityAction("复制全文") { copy(); true })
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleWidth = minOf(maxWidth * 0.86f, 420.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            MessageBubbleFrame(own, vm.highlightId == message.id,
                modifier = Modifier.widthIn(max = bubbleWidth).then(if ((message.file != null || recalled) && !message.pending)
                    Modifier.combinedClickable(enabled = !busy, onClick = { if (!recalled) vm.openFile(message) }, onLongClickLabel = "消息操作", onLongClick = showMenu)
                        .semantics { customActions = accessibleActions } else Modifier),
                menu = {
                    MessageActionsMenu(menu, { menu = false }, valid, recalled, message.kind == "text", own, canRecall, busy,
                        onQuote = { vm.quoteMessage(message); menu = false }, onCopy = { copy(); menu = false },
                        onSelect = { menu = false; selecting = true }, onDelete = { menu = false; delete = true },
                        onRecall = { menu = false; recall = true })
                }) {
                    message.replyTo?.let { QuotePreview(it, message.conversationId, vm) }
                    if (recalled) Text(if (own) "你撤回了一条消息" else "对方撤回了一条消息", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (message.kind == "text") MessageText(message.text, openLink, Modifier.testTag("message-text-${message.id}"),
                        onLongPress = if (selecting) null else showMenu, messageActions = accessibleActions)
                    else {
                        if (message.kind == "image" && !message.pending) ChatImagePreview(message, vm)
                        else Icon(if (message.kind == "image") Icons.Outlined.Image else Icons.Outlined.Description, null, Modifier.padding(bottom = 8.dp))
                        Text(message.file?.name ?: "附件", style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        message.file?.let { SupportingNote("${formatFileSize(it.size)} · 点击查看") }
                    }
            }
            if (selecting) TextButton(onClick = { selecting = false }) { Text("完成选择") }
            if (message.pending) PendingMessageActions(message.id in vm.sendingIds, vm.isWorking(Operation.Send), { vm.retry(message.id) }, { vm.discard(message.id) })
            else Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(clockTime(message.createdAt) + if (own && !recalled) " · 已发送" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val read = vm.conversations.firstOrNull { it.id == message.conversationId }?.peerReadSeq ?: 0
                if (own && vm.user?.isAdmin == true && vm.user?.readReceipts == true && message.seq <= read)
                    Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape).testTag("read-${message.id}").semantics { contentDescription = "对方已读" })
            }
        }
    }
    if (delete) Confirm("本地删除", "仅删除本机这条消息及其附件，不影响对方；本机同步和加载历史不会恢复该消息。", { delete = false }, "本地删除", true) { delete = false; vm.deleteLocalMessage(message) }
    if (recall) Confirm("撤回消息", "撤回后，普通用户不再显示这条消息，仅管理员看到撤回提示。已复制或保存的内容无法收回。", { recall = false }, "撤回消息", true) { recall = false; vm.recallMessage(message) }
}

@Composable private fun DeleteConversationConfirm(dismiss: () -> Unit, confirm: () -> Unit) {
    Confirm("删除会话", "删除你与此账号的全部历史及本机附件缓存，并从消息列表移除。对方记录和联系人关系保留；新的消息会重新显示会话，旧记录不会恢复。此操作无法撤销。", dismiss, "删除会话", true, confirm)
}
private fun messageSummary(message: ChatMessage) = when (message.kind) {
    "recalled" -> "消息已撤回"; "text" -> message.text; "image" -> "[图片]"; else -> "[文件] ${message.file?.name.orEmpty()}"
}
private fun dayKey(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).toLocalDate()
private fun dayLabel(epoch: Long): String {
    val day = dayKey(epoch)
    val today = java.time.LocalDate.now()
    return when (day) { today -> "今天"; today.minusDays(1) -> "昨天"; else -> day.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")) }
}
private fun clockTime(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
private fun shortTime(epoch: Long) = if (dayKey(epoch) == java.time.LocalDate.now()) clockTime(epoch) else dayKey(epoch).format(DateTimeFormatter.ofPattern("M/d"))
