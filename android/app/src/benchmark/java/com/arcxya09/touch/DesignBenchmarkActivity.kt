package com.arcxya09.touch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.data.ChatMessage
import com.arcxya09.touch.data.ConnectionStatus
import com.arcxya09.touch.data.Conversation
import com.arcxya09.touch.data.Person
import com.arcxya09.touch.ui.*

/** Exists only in the benchmark variant. Every row is synthetic; no app VM or repository is used. */
class DesignBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var chat by remember { mutableStateOf(false) }
            TouchTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(selected = !chat, onClick = { chat = false }, label = { Text("消息样板") })
                            FilterChip(selected = chat, onClick = { chat = true }, label = { Text("聊天样板") })
                        }
                        Box(Modifier.weight(1f)) {
                            if (chat) BenchmarkChat { chat = false } else BenchmarkInbox { chat = true }
                        }
                    }
                }
                ReportDrawnWhen { true }
            }
        }
    }
}

@Composable private fun BenchmarkInbox(open: () -> Unit) {
    val conversations = remember {
        (1..100).map { index ->
            val person = Person("synthetic-person-$index", "sample_$index", "样板联系人 $index")
            val message = ChatMessage("synthetic-message-$index", "synthetic-conversation-$index", person.id,
                "synthetic-$index", index.toLong(), "text", "这一行是第 $index 条合成会话摘要。", 1_790_849_280L - index * 60, null)
            Conversation("synthetic-conversation-$index", person, if (index % 4 == 0) index else 0, 0, true, message)
        }
    }
    // The labelled page bounds contain the real shared LazyColumn. The collector can either
    // swipe this node or locate its scrollable descendant; no fake scroll semantics are added.
    Box(Modifier.fillMaxSize().semantics { contentDescription = "样板消息列表" }) {
        ConversationListContent(conversations, true, ConnectionStatus.LIVE, loading = false, deleting = false,
            avatar = { person ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Text(person.name.takeLast(2), style = MaterialTheme.typography.titleMedium) }
                }
            }, onOpen = { open() }, onContacts = {}, onSettings = {}, onHide = {}, onDiagnostics = {}, onRetry = {}, onDelete = {})
    }
}

@Composable private fun BenchmarkChat(back: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ChatHeader("合成对话", back, back) { IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, "更多操作") } }
        BusyIndicator(false)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "样板聊天列表" },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items((1..120).toList(), key = { it }) { index ->
                val own = index % 2 == 0
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val width = minOf(maxWidth * 0.86f, 420.dp)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
                        ChatBubbleSurface(own, modifier = Modifier.widthIn(max = width)) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (index % 7 == 0) {
                                    Icon(Icons.Outlined.Description, null)
                                    Text("样本文档-$index.pdf", style = MaterialTheme.typography.titleSmall)
                                    SupportingNote("248.0 KiB · 点击查看")
                                } else MessageText("第 $index 条合成消息。今天的阳光很好，做完手头的事情，一起出去走走。", {}, onLongPress = {})
                            }
                        }
                        if (index % 13 == 0) PendingMessageActions(false, false, {}, {})
                        else Text("12:08" + if (own) " · 已发送" else "", Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        ChatComposer(draft, false, true, { draft = it }, { draft = "" }, {})
    }
}
