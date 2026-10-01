package com.arcxya09.touch.ui

import androidx.activity.compose.BackHandler
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.BuildConfig
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.data.*
import com.arcxya09.touch.security.Pattern
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun TouchRoot(vm: AppViewModel, activity: MainActivity) {
    TouchTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                if (vm.storageError && !vm.privacy) Text("本机加密数据暂时无法读取，已停止访问。请重新启动应用；原有数据不会自动清空。", Modifier.padding(32.dp))
                else if (!vm.mayShowSession || vm.screen == "timer") TimerScreen(vm, activity)
                else if (vm.user == null) LoginScreen(vm)
                else if (vm.user!!.mustChange) PasswordScreen(vm, true)
                else if (vm.needsPrivacySetup) PrivacyWelcome(vm)
                else when (vm.screen) {
                    "chat" -> ChatScreen(vm, activity)
                    "contacts" -> ContactsScreen(vm)
                    "settings" -> SettingsScreen(vm, activity)
                    "notifications" -> NotificationSettingsScreen(vm, activity)
                    "profile" -> ProfileScreen(vm, activity)
                    "password" -> PasswordScreen(vm, false)
                    "preview" -> PreviewScreen(vm, activity)
                    else -> HomeScreen(vm)
                }
            }
        }
        LaunchedEffect(vm.safety, vm.initialized) { activity.applySafety() }
        SideEffect { activity.renderedGate() }
        BackHandler(enabled = vm.mayShowChat && vm.screen != "home" && vm.user != null) {
            if (vm.screen == "timer") vm.returnFromTimer()
            else vm.screen = if (vm.screen == "preview") "chat" else "home"
        }
        BackHandler(enabled = vm.privacy && vm.mayShowChat && vm.screen == "home") { vm.hide() }
        if (vm.mayShowChat && vm.showDiagnostics) DiagnosticsDialog(vm)
        if (vm.mayShowSession) {
            vm.error?.let { message ->
                AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("提示") }, text = { Text(message) },
                    confirmButton = { TextButton(onClick = { vm.error = null }) { Text("知道了") } })
            }
            if (vm.mayShowChat && vm.pendingAvatar != null && vm.user != null && !vm.user!!.mustChange) {
                AlertDialog(onDismissRequest = { vm.pendingAvatar = null }, title = { Text("更换头像") },
                    text = { Text("将刚刚选择的图片设为头像？头像会裁剪为方形，并显示给其他用户。") },
                    confirmButton = { TextButton(onClick = vm::uploadAvatar, enabled = !vm.busy) { Text("上传头像") } },
                    dismissButton = { TextButton(onClick = { vm.pendingAvatar = null }) { Text("取消") } })
            }
            if (vm.mayShowChat && vm.pendingSelection != null && vm.user != null && !vm.user!!.mustChange) {
                AlertDialog(onDismissRequest = { vm.pendingSelection = null }, title = { Text("发送附件") },
                    text = { Text("发送刚刚选择的${if (vm.pendingSelection!!.second == "image") "图片" else "文件"}？") },
                    confirmButton = { TextButton(onClick = vm::sendSelection, enabled = !vm.busy) { Text("发送") } },
                    dismissButton = { TextButton(onClick = { vm.pendingSelection = null }) { Text("取消") } })
            }
            if (vm.mayShowChat && vm.transfer != null) {
                AlertDialog(onDismissRequest = {}, title = { Text("正在传输") }, text = {
                    Column { LinearProgressIndicator(progress = { vm.transfer ?: 0f }, modifier = Modifier.fillMaxWidth()); Text("${((vm.transfer ?: 0f) * 100).toInt()}%") }
                }, confirmButton = { TextButton(onClick = vm::cancelTransfer) { Text("取消传输") } })
            }
            if (vm.mayShowChat && vm.showUpdate && vm.update != null) {
                val update = vm.update!!
                AlertDialog(onDismissRequest = { if (vm.updateProgress == null) vm.showUpdate = false }, title = { Text("Touch ${update.versionName}") },
                    text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        Text("${"%.1f".format(update.apkSize / 1048576.0)} MiB", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp)); Text(update.changelog)
                        vm.updateProgress?.let { progress -> Spacer(Modifier.height(16.dp)); LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) }
                        if (vm.updateApk != null) Text("安装包已校验。授权安装后请返回此处继续安装。", Modifier.padding(top = 12.dp))
                    } }, confirmButton = {
                        if (vm.updateProgress == null) TextButton(onClick = {
                            val apk = vm.updateApk
                            if (apk == null) vm.downloadUpdate() else runCatching { vm.updater!!.install(apk, update) }.onFailure { vm.error = it.message }
                        }) { Text(if (vm.updateApk == null) "下载更新" else "安装更新") }
                    }, dismissButton = { TextButton(onClick = { vm.cancelUpdate(); vm.showUpdate = false }) { Text(if (vm.updateProgress == null) "稍后" else "取消") } })
            }
        }
    }
}

@Composable private fun Header(title: String, back: (() -> Unit)? = null, status: String? = null, onStatus: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "返回") }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            status?.let { Text(it, Modifier.padding(start = 8.dp).clickable(enabled = onStatus != null) { onStatus?.invoke() }.testTag("connection-status"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        actions()
    }
}
@Composable internal fun BusyIndicator(busy: Boolean) {
    Box(Modifier.fillMaxWidth().height(4.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxSize())
    }
}
@Composable private fun Busy(vm: AppViewModel) = BusyIndicator(vm.busy)
@Composable private fun Empty(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp)); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun LoginScreen(vm: AppViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("TOUCH", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("欢迎回来", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.headlineLarge)
        Text("与重要的人，保持联系。", Modifier.padding(top = 12.dp, bottom = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(username, { username = it.take(32) }, Modifier.fillMaxWidth(), label = { Text("账号") }, singleLine = true)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(password, { password = it.take(128) }, Modifier.fillMaxWidth(), label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        Button(onClick = { vm.login(username.trim(), password) }, enabled = !vm.busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 24.dp), contentPadding = PaddingValues(16.dp)) { Text(if (vm.busy) "正在登录…" else "登录") }
        Text("账号由管理员创建。如需账号或重置密码，请联系管理员。", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { vm.screen = "timer" }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 20.dp)) { Text("使用番茄钟") }
    }
}

@Composable private fun PasswordScreen(vm: AppViewModel, forced: Boolean) {
    var old by remember { mutableStateOf("") }; var new by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header(if (forced) "设置新密码" else "修改密码", if (forced) null else ({ vm.screen = "settings" }))
        Busy(vm)
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (forced) "首次登录，请将临时密码改为个人密码。" else "新密码至少 10 位。")
            OutlinedTextField(old, { old = it.take(128) }, label = { Text("当前密码") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(new, { new = it.take(128) }, label = { Text("新密码") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirm, { confirm = it.take(128) }, label = { Text("再次输入新密码") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.password(old, new) }, enabled = !vm.busy && new.length >= 10 && new == confirm && new != old && old.isNotEmpty()) { Text("保存密码") }
            if (forced) TextButton(onClick = vm::logout) { Text("退出登录") }
        }
    }
}

@Composable private fun PrivacyWelcome(vm: AppViewModel) {
    var setup by remember { mutableStateOf(true) }
    BackHandler { setup = false }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("设置隐私模式", style = MaterialTheme.typography.headlineMedium)
        Text("默认以番茄钟作为入口。设置并试用手势后，只有绘制正确图案才能进入。", Modifier.padding(vertical = 24.dp))
        Button(onClick = { setup = true }, enabled = !vm.busy) { Text("设置解锁图案") }
        TextButton(onClick = vm::skipPrivacySetup, enabled = !vm.busy) { Text("跳过设置，使用正常模式") }
        Busy(vm)
    }
    if (setup && !vm.busy) PatternSetup({ setup = false }) { pattern -> setup = false; vm.setPrivacy(pattern) }
}

@Composable private fun HomeScreen(vm: AppViewModel) {
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    deleting?.let { item -> DeleteConversationConfirm({ deleting = null }) { vm.deleteConversation(item.id); deleting = null } }
    Column(Modifier.fillMaxSize()) {
        Header("消息", status = vm.connectionStatus.label, onStatus = vm::diagnostics) {
            IconButton(onClick = { vm.screen = "contacts" }) { Icon(Icons.Outlined.PersonAdd, "联系人") }
            IconButton(onClick = { vm.screen = "settings" }) { Icon(Icons.Outlined.Settings, "设置") }
            IconButton(onClick = vm::hide) { Icon(Icons.Outlined.Timer, "返回番茄钟") }
        }
        Busy(vm)
        if (vm.conversations.isEmpty()) Empty("还没有会话", "添加联系人，开始第一段对话。")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.conversations, key = { it.id }) { conversation ->
                Surface(Modifier.fillMaxWidth().clickable { vm.openConversation(conversation.id) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(conversation.peer, vm)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(conversation.peer.name, fontWeight = FontWeight.SemiBold)
                            Text(conversation.last?.let { if (it.kind == "recalled") "消息已撤回" else if (it.kind == "text") it.text else if (it.kind == "image") "[图片]" else "[文件] ${it.file?.name.orEmpty()}" } ?: "开始聊天",
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (conversation.unread > 0) Badge { Text(conversation.unread.coerceAtMost(99).toString()) }
                        IconButton(onClick = { deleting = conversation }, enabled = !vm.busy) { Icon(Icons.Outlined.DeleteOutline, "删除与${conversation.peer.name}的会话") }
                    }
                }
            }
        }
    }
}
@Composable private fun Avatar(person: Person, vm: AppViewModel) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, person.id, person.avatarVersion, vm.user?.id) {
        value = null
        value = runCatching { vm.repository.avatarBytes(person)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), "${person.name}的头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Text(person.name.take(1), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable private fun ProfileScreen(vm: AppViewModel, activity: MainActivity) {
    val person = vm.user ?: return
    var name by remember(person.id) { mutableStateOf(person.name) }
    var bio by remember(person.id) { mutableStateOf(person.bio) }
    Column(Modifier.fillMaxSize()) {
        Header("个人资料", { vm.screen = "settings" }); Busy(vm)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(person, vm)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = activity::chooseAvatar, enabled = !vm.busy) { Text("更换头像") }
                if (person.avatarVersion != null) TextButton(onClick = vm::removeAvatar, enabled = !vm.busy) { Text("移除头像") }
            }
            Text("@${person.username} · 账号名不可修改", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth(), label = { Text("昵称") }, singleLine = true)
            OutlinedTextField(bio, { bio = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("个人简介") }, maxLines = 4)
            Text("昵称、头像和简介会显示给其他用户。头像最大 5 MiB。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.saveProfile(name, bio) }, enabled = !vm.busy && name.isNotBlank()) { Text("保存资料") }
        }
    }
}

@Composable private fun ContactsScreen(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<Person?>(null) }
    Column(Modifier.fillMaxSize()) {
        Header("联系人", { vm.screen = "home" }); Busy(vm)
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it.take(32) }, Modifier.weight(1f), label = { Text("输入完整账号") }, singleLine = true)
            Button(onClick = { vm.search(query) }, enabled = query.isNotBlank() && !vm.busy) { Text("查找") }
        }
        vm.foundPerson?.let { person ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(person, vm)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(person.name); Text("@${person.username}", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = vm::request, enabled = !vm.busy) { Text(if (vm.user?.isAdmin == true) "直接添加" else "发送申请") }
                }
            }
        }
        if (vm.contacts.isEmpty()) Empty("还没有联系人", if (vm.user?.isAdmin == true) "按完整账号查找，管理员可直接添加好友。" else "只支持精确账号查找，申请需经对方同意。")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.contacts, key = { it.id }) { contact ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(contact.peer, vm)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(contact.peer.name, fontWeight = FontWeight.SemiBold); Text("@${contact.peer.username}", style = MaterialTheme.typography.bodySmall); if (contact.peer.bio.isNotBlank()) Text(contact.peer.bio, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            when {
                                contact.state == "accepted" -> {
                                    TextButton(onClick = { remove = contact.peer }) { Text("删除") }
                                    TextButton(onClick = { contact.conversationId?.let(vm::openConversation) }) { Text("聊天") }
                                }
                                contact.incoming -> {
                                    TextButton(onClick = { vm.contactAction(contact.peer, "reject") }, enabled = !vm.busy) { Text("拒绝") }
                                    TextButton(onClick = { vm.contactAction(contact.peer, "accept") }, enabled = !vm.busy) { Text("接受") }
                                }
                                else -> Text("等待对方确认", Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    remove?.let { person -> Confirm("删除联系人", "删除后双方无法继续发送消息，已有历史记录保留。", { remove = null }) { vm.contactAction(person, "remove"); remove = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ChatScreen(vm: AppViewModel, activity: MainActivity) {
    val conversation = vm.conversations.firstOrNull { it.id == vm.conversationId }
    val peer = conversation?.peer ?: vm.contacts.firstOrNull { it.conversationId == vm.conversationId }?.peer
    // Chat text must never be serialized into Android's plaintext saved-instance state.
    val draft = vm.draftText
    var attachments by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    val lastId = vm.messages.lastOrNull()?.id
    var atBottom by remember { mutableStateOf(true) }
    var newMessages by remember { mutableStateOf(false) }
    LaunchedEffect(scroll) { snapshotFlow { !scroll.canScrollForward }.collect { atBottom = it; if (it) newMessages = false } }
    LaunchedEffect(dragging, atBottom) { if (dragging && !atBottom) vm.holdHistory() }
    val hasIncoming = (conversation?.last?.seq ?: 0L) > (vm.messages.filterNot { it.pending }.lastOrNull()?.seq ?: Long.MAX_VALUE)
    LaunchedEffect(lastId) {
        if (vm.messages.isNotEmpty()) {
            if (atBottom && !vm.browsingHistory) scroll.animateScrollToItem(vm.messages.size + 1)
            else newMessages = true
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
        Header(peer?.name ?: "聊天", { vm.screen = "home" }) {
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("隐藏聊天") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                        onClick = { more = false; vm.hide() })
                    DropdownMenuItem(text = { Text("连接诊断") }, onClick = { more = false; vm.diagnostics() })
                    DropdownMenuItem(text = { Text("删除会话") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                        enabled = !vm.busy, onClick = { more = false; clear = true })
                }
            }
        }
        Busy(vm)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { if (vm.hasMore) TextButton(onClick = vm::older, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("加载更早消息") } }
                items(vm.messages, key = { it.id }) { message -> MessageBubble(message, vm, activity::openWebLink) }
                item(key = "chat-bottom") { Spacer(Modifier.height(1.dp)) }
            }
            if (!atBottom || newMessages || hasIncoming || vm.browsingHistory) {
                Surface(
                    onClick = { vm.latest(); newMessages = false },
                    enabled = !vm.busy,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(48.dp).testTag("jump-to-latest"),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
                    shadowElevation = 3.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.KeyboardArrowDown, if (newMessages || hasIncoming) "有新消息，返回最新" else "返回最新消息")
                    }
                }
            }
        }
        vm.quote?.let { ref ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { QuotePreview(ref, vm.conversationId.orEmpty(), vm, clickable = false) }
                IconButton(onClick = vm::cancelQuote) { Icon(Icons.Outlined.Close, "取消引用") }
            }
        }
        if (conversation?.canSend == false) Text("当前无法发送，请先建立有效联系人关系。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(draft, vm::editDraft, Modifier.weight(1f),
                leadingIcon = {
                    IconButton(onClick = { attachments = true }, enabled = !vm.busy) { Icon(Icons.Outlined.Add, "添加图片或文件") }
                }, placeholder = { Text("输入消息") }, maxLines = 5, shape = RoundedCornerShape(20.dp))
            IconButton(onClick = { vm.send(draft) {} }, enabled = draft.isNotBlank() && !vm.busy) { Icon(Icons.Outlined.Send, "发送") }
        }
    }
    if (attachments) {
        ModalBottomSheet(onDismissRequest = { attachments = false }) {
            Text("添加内容", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            ListItem(headlineContent = { Text("图片") }, leadingContent = { Icon(Icons.Outlined.Image, null) },
                modifier = Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { attachments = false; activity.chooseImage() })
            ListItem(headlineContent = { Text("文件") }, leadingContent = { Icon(Icons.Outlined.AttachFile, null) },
                modifier = Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { attachments = false; activity.chooseDocument() })
            Spacer(Modifier.height(24.dp))
        }
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
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
        Surface(shape = RoundedCornerShape(18.dp), color = if (vm.highlightId == message.id) MaterialTheme.colorScheme.tertiaryContainer else if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 310.dp).then(if ((message.file != null || recalled) && !message.pending) Modifier.combinedClickable(enabled = !vm.busy, onClick = { if (!recalled) vm.openFile(message) }, onLongClick = { if (valid) menu = true }) else Modifier)) {
            Column(Modifier.padding(14.dp)) {
                message.replyTo?.let { QuotePreview(it, message.conversationId, vm) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("本地删除") }, enabled = valid && !vm.busy, onClick = { menu = false; delete = true })
                    if (own && !recalled) DropdownMenuItem(text = { Text("撤回消息") }, enabled = valid && !vm.busy,
                        onClick = { menu = false; recall = true })
                    DropdownMenuItem(text = { Text("引用回复") }, enabled = valid && !recalled, onClick = { vm.quoteMessage(message); menu = false })
                    if (message.kind == "text") {
                        DropdownMenuItem(text = { Text("复制全文") }, onClick = {
                            scope.launch {
                                val clip = android.content.ClipData.newPlainText("", message.text)
                                clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(clip))
                            }; menu = false
                        })
                        DropdownMenuItem(text = { Text("选择文字") }, onClick = { menu = false; selecting = true })
                    }
                }
                if (recalled) Text(if (own) "你撤回了一条消息" else "对方撤回了一条消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (message.kind == "text") MessageText(message.text, openLink, Modifier.testTag("message-text-${message.id}"), onLongPress = if (selecting) null else ({ if (valid) menu = true }))
                else {
                    if (message.kind == "image" && !message.pending) ChatImagePreview(message, vm)
                    else Icon(if (message.kind == "image") Icons.Outlined.Image else Icons.Outlined.Description, null)
                    Text(message.file?.name ?: "附件", fontWeight = FontWeight.Medium)
                    message.file?.let { Text("${"%.1f".format(it.size / 1024.0)} KiB · 点击查看", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        if (selecting) TextButton(onClick = { selecting = false }) { Text("完成选择") }
        if (message.pending) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (message.id in vm.sendingIds) "发送中" else "发送失败", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { vm.retry(message.id) }, enabled = !vm.busy && message.id !in vm.sendingIds) { Text("重试") }
            TextButton(onClick = { vm.discard(message.id) }, enabled = !vm.busy) { Text("删除") }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(message.createdAt * 1000)) + if (own && !recalled) " · 已发送" else "",
                Modifier.padding(horizontal = 4.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val read = vm.conversations.firstOrNull { it.id == message.conversationId }?.peerReadSeq ?: 0
            if (own && vm.user?.isAdmin == true && vm.user?.readReceipts == true && message.seq <= read)
                Box(Modifier.size(3.dp).background(Color.Gray, CircleShape).testTag("read-${message.id}").semantics { contentDescription = "对方已读" })
        }
    }
    if (delete) Confirm("本地删除", "仅删除本机这条消息及其附件，不影响对方；本机同步和加载历史不会恢复该消息。", { delete = false }) {
        delete = false; vm.deleteLocalMessage(message)
    }
    if (recall) Confirm("撤回消息", "撤回后，普通用户不再显示这条消息，仅管理员看到撤回提示。已复制或保存的内容无法收回。", { recall = false }) {
        recall = false; vm.recallMessage(message)
    }

}

@Composable private fun SettingsScreen(vm: AppViewModel, activity: MainActivity) {
    var passwordDialog by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    var enableRetentionDialog by remember { mutableStateOf(false) }
    var retentionDialog by remember { mutableStateOf(false) }
    var hours by remember(vm.retentionSeconds) { mutableStateOf((vm.retentionSeconds / 3600).toString()) }
    Column(Modifier.fillMaxSize()) {
        Header("设置", { vm.screen = "home" }); Busy(vm)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            vm.user?.let { Avatar(it, vm) }
            TextButton(onClick = { vm.screen = "notifications" }) { Text("通知与后台运行") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本机加密草稿", Modifier.weight(1f))
                Switch(checked = vm.saveDrafts, onCheckedChange = vm::enableDrafts, enabled = !vm.busy)
            }
            Text("默认关闭。开启后仅加密保存文字和引用标识，不保存附件选择；定时销毁同时适用于草稿。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.screen = "profile" }) { Text("编辑个人资料") }
            Text(vm.user?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
            Text("@${vm.user?.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("本地定时销毁", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = vm.retentionEnabled, onCheckedChange = { if (it) enableRetentionDialog = true else vm.enableRetention(false) }, enabled = !vm.busy,
                    modifier = Modifier.semantics { contentDescription = "本地定时销毁开关" })
            }
            Text(if (vm.retentionEnabled) "保留最近 ${vm.retentionSeconds / 3600} 小时。过期消息、待发送内容和附件会从本机清理，且不再拉取。服务器及其他账号不受影响。" else "已关闭，不按时间清理本机记录。此前已销毁的记录仍不会重新拉取。开启时默认保留 1 小时。")
            OutlinedButton(onClick = { retentionDialog = true }, enabled = vm.retentionEnabled && !vm.busy) { Text("设置保留时间") }
            Text("数据库和附件均加密保存，密钥由 Android Keystore 保护。关机、强行停止或系统限制后台时，清理会延后；再次打开前先清理。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            if (vm.user?.isAdmin == true) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("显示已读标识", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = vm.user?.readReceipts == true, onCheckedChange = vm::setReadReceipts, enabled = !vm.busy,
                        modifier = Modifier.semantics { contentDescription = "显示已读标识开关" })
                }
                Text("仅管理员可用。开启后，已发送消息旁的灰色小点表示对方已读；没有小点表示未读。", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
            SafetySwitch("从最近任务隐藏", vm.safety.hideRecents, !vm.busy) { vm.saveSafety(vm.safety.copy(hideRecents = it)) }
            Text("隐藏整个应用卡片，可从桌面图标重新打开。", style = MaterialTheme.typography.bodySmall)
            SafetySwitch("翻面退出", vm.safety.flipExit, !vm.busy && activity.hasMotionSensor()) { vm.saveSafety(vm.safety.copy(flipExit = it)) }
            SafetySwitch("摇一摇退出", vm.safety.shakeExit, !vm.busy && activity.hasMotionSensor()) { vm.saveSafety(vm.safety.copy(shakeExit = it)) }
            Text(if (activity.hasMotionSensor()) "仅在前台识别：将屏幕朝下保持片刻，或连续明显摇动三次。退出前遮盖内容；后台提醒保持原设置，未发送内容可能丢失。" else "此设备没有可用的加速度传感器。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("隐私模式", style = MaterialTheme.typography.titleMedium)
            Text(if (vm.privacy) "已开启。离开前台后立即回到番茄钟。" else "开启后，需在番茄钟上绘制隐藏图案才能进入聊天。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { passwordDialog = "setup" }) { Text(if (vm.privacy) "更改图案" else "设置并开启") }
                if (vm.privacy) OutlinedButton(onClick = { passwordDialog = "disable" }) { Text("关闭") }
            }
            Text("忘记图案需清除应用数据后重新登录。聊天记录保存在服务器，本机草稿和设置会清除。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            TextButton(onClick = { vm.screen = "password" }) { Text("修改账号密码") }
            TextButton(onClick = { vm.screen = "timer" }) { Text("打开番茄钟") }
            TextButton(onClick = { vm.checkUpdate() }) { Text("检查更新 · ${BuildConfig.VERSION_NAME}") }
            if (vm.updateApk != null && vm.update != null) TextButton(onClick = { vm.showUpdate = true }) { Text("继续安装已下载的更新") }
            Text("隐私模式仅在解锁后检查更新。可在通知与后台运行中开启提醒；重新打开会补齐离线消息。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { clearHistory = true }, enabled = !vm.busy) { Text("清空本地聊天记录") }
            OutlinedButton(onClick = { logout = true }) { Text("退出账号") }
        }
    }
    if (clearHistory) Confirm("清空本地聊天记录", "清除本机已有消息、附件、草稿和待发送内容，旧消息不再自动拉取。保留登录、联系人和设置，不影响服务器及对方记录。", { clearHistory = false }) {
        clearHistory = false; vm.clearLocalHistory()
    }
    passwordDialog?.let { action ->
        var password by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { passwordDialog = null }, title = { Text("验证账号密码") },
            text = { OutlinedTextField(password, { password = it.take(128) }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = { TextButton(onClick = {
                if (action == "disable") { vm.disablePrivacy(password); passwordDialog = null }
                else vm.verifyPrivacyPassword(password) { passwordDialog = null; setup = true }
            }, enabled = !vm.busy && password.isNotBlank()) { Text("确认") } }, dismissButton = { TextButton(onClick = { passwordDialog = null }) { Text("取消") } })
    }
    if (setup) PatternSetup({ setup = false }) { pattern -> setup = false; vm.setPrivacy(pattern) }
    if (enableRetentionDialog) Confirm("开启本地定时销毁", "默认仅保留最近 1 小时。更早的本机记录与附件将立即清理且不再拉取；服务器和其他账号不受影响。", { enableRetentionDialog = false }) {
        enableRetentionDialog = false; vm.enableRetention(true)
    }
    if (retentionDialog) AlertDialog(onDismissRequest = { retentionDialog = false }, title = { Text("本机保留时间") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1 小时" to 1, "24 小时" to 24, "7 天" to 168).forEach { (label, value) ->
                    TextButton(onClick = { hours = value.toString() }) { Text(label) }
                }
            }
            OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(4) }, label = { Text("小时（1–8760）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            Text("按消息发送时间计算。缩短后立即清理；延长也不会恢复已销毁的历史。本机截止时间在退出账号后保留，清除应用数据或重装会重置。")
        } }, confirmButton = { TextButton(onClick = { vm.setRetention(hours.toLong()); retentionDialog = false },
            enabled = !vm.busy && hours.toLongOrNull()?.let { it in 1..8760 } == true) { Text("确认并应用") } },
        dismissButton = { TextButton(onClick = { retentionDialog = false }) { Text("取消") } })
    if (logout) Confirm("退出账号", "清理本机聊天缓存和凭证，服务器记录保留。隐私模式设置保留。", { logout = false }) { logout = false; vm.logout() }
}

@Composable private fun PatternSetup(dismiss: () -> Unit, save: (String) -> Unit) {
    var stage by remember { mutableIntStateOf(0) }
    var pattern by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("请至少连接 4 个不同点") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(listOf("绘制图案", "再次确认", "隐藏图案试解锁")[stage]) },
        text = { Column {
            Text(hint)
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (stage == 2) Text("25:00", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PatternPad(Modifier.fillMaxSize().testTag("setup-pattern"), stage < 2) { points ->
                    when {
                        !Pattern.valid(points) -> hint = "请至少连接 4 个不同点"
                        stage == 0 -> { pattern = Pattern.encode(points); stage = 1; hint = "按相同顺序再次绘制" }
                        Pattern.encode(points) != pattern -> hint = "图案不一致，请重试"
                        stage == 1 -> { stage = 2; hint = "点位仍在相同位置，这次不显示点和轨迹" }
                        else -> save(pattern)
                    }
                }
            }
            Text("计时盘的上、中、下各有三个点位。跨过中点时会自动选中。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun DeleteConversationConfirm(dismiss: () -> Unit, confirm: () -> Unit) {
    Confirm("删除会话", "删除你与此账号的全部历史及本机附件缓存，并从消息列表移除。对方记录和联系人关系保留；新的消息会重新显示会话，旧记录不会恢复。此操作无法撤销。", dismiss, confirm)
}

@Composable fun Confirm(title: String, text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = confirm) { Text("确认") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun SafetySwitch(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, change, enabled = enabled, modifier = Modifier.semantics { contentDescription = label + "开关" })
    }
}
