package com.arcxya09.touch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.data.*
import com.arcxya09.touch.timer.TimerState
import java.time.LocalDate
import java.time.ZoneId

internal enum class DesignFixture { Timer, Inbox, Chat }

/** Synthetic, offline presentation fixtures. No VM, credentials, repository or network is constructed. */
@Composable internal fun DesignFixtureScreen(screen: DesignFixture, dark: Boolean, error: Boolean = false) {
    TouchTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize()) {
            when (screen) {
                DesignFixture.Timer -> TimerContent(TimerState(25, 5, false, false, 25 * 60000L, false), {}, {}, {}, {}, permissions = {
                    if (error) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        TextButton(onClick = {}) { Text("通知未开启，后台提醒无法显示", style = MaterialTheme.typography.labelMedium) }
                    }
                })
                DesignFixture.Inbox -> SampleInbox(error)
                DesignFixture.Chat -> SampleConversation(error)
            }
        }
    }
}

@Composable private fun SampleInbox(error: Boolean) {
    val people = listOf(Person("fixture-a", "sample_a", "小林"), Person("fixture-b", "sample_b", "阿远"), Person("fixture-c", "sample_c", "小满"))
    val time = LocalDate.now().atTime(12, 8).atZone(ZoneId.systemDefault()).toEpochSecond()
    val messages = listOf("今天的阳光很好，出去走走吗？", "这份笔记已经整理好了。", "慢慢来，我们周末见。")
    val rows = people.mapIndexed { index, person -> Conversation("fixture-$index", person, if (index == 0) 2 else 0, 0, true,
        ChatMessage("sample-$index", "fixture-$index", person.id, "sample", index.toLong(), "text", messages[index], time - index * 3600, null)) }
    ConversationListContent(if (error) emptyList() else rows, !error, if (error) ConnectionStatus.RETRYING else ConnectionStatus.LIVE,
        loading = false, deleting = false, avatar = { person ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Text(person.name.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }, onOpen = {}, onContacts = {}, onSettings = {}, onHide = {}, onDiagnostics = {}, onRetry = {}, onDelete = {})
}

@Composable private fun SampleConversation(error: Boolean) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ChatHeader("小林", {}, {}) { IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, "更多操作") } }
        BusyIndicator(false)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { SupportingNote("今天") } }
                item { SampleMessage("今天的阳光很好，出去走走吗？", false, "12:08") }
                item { SampleMessage("好呀。等我把手头这一段做完。", true, "12:09") }
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                        ChatBubbleSurface(false, modifier = Modifier.widthIn(max = 280.dp)) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Description, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("周末散步路线.pdf", style = MaterialTheme.typography.titleSmall)
                                SupportingNote("248.0 KiB · 点击查看")
                            }
                        }
                        SupportingNote("12:10", Modifier.padding(4.dp))
                    }
                }
                item { SampleMessage(if (error) "十分钟后见。" else "十分钟后见。带上相机吧。", true, "12:11", error) }
            }
        }
        ChatComposer(draft, false, true, { draft = it }, { draft = "" }, {})
    }
}

@Composable private fun SampleMessage(text: String, own: Boolean, time: String, failed: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleWidth = minOf(maxWidth * 0.86f, 420.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            ChatBubbleSurface(own, modifier = Modifier.widthIn(max = bubbleWidth)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) { MessageText(text, {}, onLongPress = {}) }
            }
            if (failed) PendingMessageActions(false, false, {}, {})
            else Text(time + if (own) " · 已发送" else "", Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(name = "Inbox · Light", widthDp = 360, heightDp = 800)
@Composable private fun InboxLight() = DesignFixtureScreen(DesignFixture.Inbox, false)
@Preview(name = "Inbox · Dark", widthDp = 360, heightDp = 800)
@Composable private fun InboxDark() = DesignFixtureScreen(DesignFixture.Inbox, true)
@Preview(name = "Inbox · Offline", widthDp = 360, heightDp = 800)
@Composable private fun InboxOffline() = DesignFixtureScreen(DesignFixture.Inbox, false, true)
@Preview(name = "Conversation · Light", widthDp = 360, heightDp = 800)
@Composable private fun ConversationLight() = DesignFixtureScreen(DesignFixture.Chat, false)
@Preview(name = "Conversation · Dark", widthDp = 360, heightDp = 800)
@Composable private fun ConversationDark() = DesignFixtureScreen(DesignFixture.Chat, true)
@Preview(name = "Conversation · Retry", widthDp = 360, heightDp = 800)
@Composable private fun ConversationRetry() = DesignFixtureScreen(DesignFixture.Chat, false, true)
@Preview(name = "Timer · Permissions", widthDp = 360, heightDp = 800)
@Composable private fun TimerPermissions() = DesignFixtureScreen(DesignFixture.Timer, false, true)
