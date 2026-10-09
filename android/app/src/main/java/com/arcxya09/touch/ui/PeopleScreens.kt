package com.arcxya09.touch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.Operation
import com.arcxya09.touch.data.Person

@Composable internal fun ProfileScreen(vm: AppViewModel, activity: MainActivity) {
    val person = vm.user ?: return
    var name by remember(person.id) { mutableStateOf(person.name) }
    var bio by remember(person.id) { mutableStateOf(person.bio) }
    var discard by remember { mutableStateOf(false) }
    val working = vm.isWorking(Operation.Profile)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val changed = name != person.name || bio != person.bio
    val back = { if (changed) discard = true else vm.back() }
    BackHandler(enabled = changed) { discard = true }
    TouchPage("个人资料", back, working) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(person, vm, 88.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = activity::chooseAvatar, enabled = !working) { Text("更换头像") }
                if (person.avatarVersion != null) TextButton(onClick = vm::removeAvatar, enabled = !working) { Text("移除头像") }
            }
        }
        SupportingNote("@${person.username} · 账号名不可修改")
        OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth(), label = { Text("昵称") }, singleLine = true,
            isError = name.isBlank(), supportingText = { Text(if (name.isBlank()) "昵称不能为空" else "${name.length} / 64") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        OutlinedTextField(bio, { bio = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("个人简介") }, minLines = 3, maxLines = 5,
            supportingText = { Text("${bio.length} / 160") })
        SupportingNote("昵称、头像和简介会显示给其他用户。头像最大 5 MiB。")
        Button(onClick = { focus.clearFocus(); keyboard?.hide(); vm.saveProfile(name, bio) }, enabled = !working && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (working) "正在保存…" else "保存资料") }
    }
    if (discard) Confirm("放弃资料修改？", "尚未保存的昵称与简介将被放弃。", { discard = false }, "放弃修改") { discard = false; vm.back() }
}

@Composable internal fun ContactsScreen(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<Person?>(null) }
    val working = vm.isWorking(Operation.Contacts)
    Column(Modifier.fillMaxSize()) {
        TouchHeader("联系人", vm::back)
        BusyIndicator(working)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    OutlinedTextField(query, { query = it.take(32) }, Modifier.fillMaxWidth(), label = { Text("输入完整账号") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
                            IconButton(onClick = { vm.search(query) }, enabled = query.isNotBlank() && !working) { Icon(Icons.Outlined.ArrowForward, "查找") }
                        }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank() && !working) vm.search(query) }))
                }
                vm.foundPerson?.let { person -> item {
                    SectionLabel("查找结果")
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ContactIdentity(person, vm)
                            Button(onClick = vm::request, enabled = !working, modifier = Modifier.align(Alignment.End)) { Text(if (vm.user?.isAdmin == true) "直接添加" else "发送申请") }
                        }
                    }
                } }
                if (vm.contacts.isEmpty() && !working) item { EmptyState("还没有联系人", if (vm.user?.isAdmin == true) "按完整账号查找，管理员可直接添加好友。" else "只支持精确账号查找，申请需经对方同意。", Icons.Outlined.PeopleOutline) }
                items(vm.contacts, key = { it.id }) { contact ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ContactIdentity(contact.peer, vm)
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                when {
                                    contact.state == "accepted" -> {
                                        TextButton(onClick = { remove = contact.peer }, enabled = !working) { Text("删除") }
                                        FilledTonalButton(onClick = { contact.conversationId?.let(vm::openConversation) }, enabled = !working && contact.conversationId != null) {
                                            Text(if (contact.conversationId == null) "正在同步会话…" else "聊天")
                                        }
                                    }
                                    contact.incoming -> {
                                        TextButton(onClick = { vm.contactAction(contact.peer, "reject") }, enabled = !working) { Text("拒绝") }
                                        FilledTonalButton(onClick = { vm.contactAction(contact.peer, "accept") }, enabled = !working) { Text("接受") }
                                    }
                                    else -> SupportingNote("等待对方确认", Modifier.padding(vertical = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    remove?.let { person -> Confirm("删除联系人", "删除后双方无法继续发送消息，已有历史记录保留。", { remove = null }, "删除联系人", true) { vm.contactAction(person, "remove"); remove = null } }
}

@Composable private fun ContactIdentity(person: Person, vm: AppViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(person, vm)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(person.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SupportingNote("@${person.username}")
            if (person.bio.isNotBlank()) Text(person.bio, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
