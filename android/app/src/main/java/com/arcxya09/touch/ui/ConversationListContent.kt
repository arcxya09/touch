package com.arcxya09.touch.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Shared by the real inbox and the offline design fixtures. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun ConversationListContent(conversations: List<Conversation>, loaded: Boolean, connection: ConnectionStatus,
    loading: Boolean, deleting: Boolean, avatar: @Composable (Person) -> Unit, onOpen: (String) -> Unit,
    onContacts: () -> Unit, onSettings: () -> Unit, onHide: () -> Unit, onDiagnostics: () -> Unit,
    onRetry: () -> Unit, onDelete: (Conversation) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TouchHeader("消息", status = connection.label, onStatus = onDiagnostics) {
            IconButton(onClick = onContacts) { Icon(Icons.Outlined.PersonAdd, "联系人") }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "设置") }
            IconButton(onClick = onHide) { Icon(Icons.Outlined.Timer, "返回番茄钟") }
        }
        BusyIndicator(loading)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (!loaded && conversations.isEmpty() && connection == ConnectionStatus.RETRYING) {
                EmptyState("暂时无法连接", "连接恢复后会同步会话。你也可以现在重试。", Icons.Outlined.CloudOff,
                    actionLabel = "重试", action = onRetry, modifier = Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()))
            } else if (!loaded && conversations.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    SupportingNote("正在整理会话…")
                }
            } else if (conversations.isEmpty()) EmptyState("还没有会话", "添加联系人，开始第一段对话。",
                actionLabel = "添加联系人", action = onContacts, modifier = Modifier.align(Alignment.Center).verticalScroll(rememberScrollState()))
            else LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                items(conversations, key = { it.id }) { conversation ->
                    var menu by remember(conversation.id) { mutableStateOf(false) }
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(conversation.id) },
                            onLongClickLabel = "会话操作", onLongClick = { menu = true })) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            avatar(conversation.peer)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(conversation.peer.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    conversation.last?.let { SupportingNote(conversationTime(it.createdAt)) }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(conversation.last?.let { when (it.kind) { "recalled" -> "消息已撤回"; "text" -> it.text; "image" -> "[图片]"; else -> "[文件] ${it.file?.name.orEmpty()}" } } ?: "开始聊天",
                                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (conversation.unread > 0) Badge { Text(if (conversation.unread > 99) "99+" else conversation.unread.toString()) }
                                }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "与${conversation.peer.name}的会话操作") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("删除会话") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                        enabled = !deleting, onClick = { menu = false; onDelete(conversation) })
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 78.dp, end = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
            }
        }
    }
}

private fun conversationTime(epoch: Long): String {
    val time = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault())
    return time.format(DateTimeFormatter.ofPattern(if (time.toLocalDate() == LocalDate.now()) "HH:mm" else "M/d"))
}
