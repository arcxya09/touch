package com.arcxya09.touch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.BuildConfig
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.Operation
import com.arcxya09.touch.Screen

@Composable internal fun SettingsScreen(vm: AppViewModel) {
    var logout by remember { mutableStateOf(false) }
    TouchPage("设置", vm::back) {
        vm.user?.let { person ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(person, vm, 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(person.name, style = MaterialTheme.typography.headlineSmall)
                    SupportingNote("@${person.username}")
                }
            }
        }
        SectionLabel("账号与资料")
        SettingsGroup {
            SettingItem("编辑个人资料", "昵称、头像与简介", Icons.Outlined.Person) { vm.navigate(Screen.Profile) }
            SettingItem("修改账号密码", icon = Icons.Outlined.Key) { vm.navigate(Screen.Password) }
        }
        SectionLabel("偏好设置")
        SettingsGroup {
            SettingItem("隐私与安全", if (vm.privacy) "隐私模式已开启" else "隐私模式未开启", Icons.Outlined.Shield) { vm.navigate(Screen.PrivacySecurity) }
            SettingItem("本机数据", if (vm.retentionEnabled) "保留最近 ${vm.retentionSeconds / 3600} 小时" else "定时销毁已关闭", Icons.Outlined.Storage) { vm.navigate(Screen.LocalData) }
            SettingItem("通知与后台运行", if (vm.alertOptions.enabled) "消息通知已开启" else "消息通知已关闭", Icons.Outlined.NotificationsNone) { vm.navigate(Screen.Notifications) }
        }
        SectionLabel("屏幕旋转")
        SettingsGroup {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                ToggleSetting("聊天页锁定竖屏", checked = vm.lockChatRotation, onChange = vm::setChatRotationLocked)
                Column(Modifier.padding(start = 20.dp)) {
                    ToggleSetting("允许图片预览旋转", checked = vm.rotateImagePreview,
                        enabled = vm.lockChatRotation, onChange = vm::setImagePreviewRotation)
                }
            }
        }
        SectionLabel("Touch")
        SettingsGroup {
            SettingItem("打开番茄钟", icon = Icons.Outlined.Timer) { vm.navigate(Screen.Timer) }
            SettingItem("关于与更新", "Touch ${BuildConfig.VERSION_NAME}", Icons.Outlined.Info) { vm.navigate(Screen.About) }
        }
        TextButton(onClick = { logout = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("退出账号") }
    }
    if (logout) Confirm("退出账号", "清理本机聊天缓存和凭证，服务器记录保留。隐私模式设置保留。", { logout = false }, "退出账号", true) { logout = false; vm.logout() }
}

@Composable internal fun PrivacySecurityScreen(vm: AppViewModel, activity: MainActivity) {
    var passwordDialog by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(false) }
    val working = vm.isWorking(Operation.Settings) || vm.isWorking(Operation.Session)
    TouchPage("隐私与安全", vm::back, working) {
        SectionLabel("隐私模式")
        Text(if (vm.privacy) "已开启。离开前台后立即回到番茄钟。" else "开启后，需在番茄钟上绘制隐藏图案才能进入聊天。", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { passwordDialog = "setup" }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(if (vm.privacy) "更改图案" else "设置并开启") }
            if (vm.privacy) OutlinedButton(onClick = { passwordDialog = "disable" }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("关闭隐私模式") }
        }
        SupportingNote("忘记图案需清除应用数据后重新登录。聊天记录保存在服务器，本机草稿和设置会清除。")
        HorizontalDivider()
        SectionLabel("快速保护")
        ToggleSetting("从最近任务隐藏", "隐藏整个应用卡片，可从桌面图标重新打开。", vm.safety.hideRecents, !working) { vm.saveSafety(vm.safety.copy(hideRecents = it)) }
        ToggleSetting("翻面退出", "将屏幕朝下保持片刻。", vm.safety.flipExit, !working && activity.hasMotionSensor()) { vm.saveSafety(vm.safety.copy(flipExit = it)) }
        ToggleSetting("摇一摇退出", "连续明显摇动三次。", vm.safety.shakeExit, !working && activity.hasMotionSensor()) { vm.saveSafety(vm.safety.copy(shakeExit = it)) }
        SupportingNote(if (activity.hasMotionSensor()) "仅在前台识别。退出前立即遮盖内容；后台提醒保持原设置，未发送内容可能丢失。" else "此设备没有可用的加速度传感器。")
        if (vm.user?.isAdmin == true) {
            HorizontalDivider()
            SectionLabel("消息状态")
            ToggleSetting("显示已读标识", "仅管理员可用。开启后，已发送消息旁的灰色小点表示对方已读；没有小点表示未读。", vm.user?.readReceipts == true, !vm.isWorking(Operation.Profile), vm::setReadReceipts)
        }
    }
    passwordDialog?.let { action ->
        var password by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { passwordDialog = null }, title = { Text("验证账号密码") },
            text = { PasswordField(password, { password = it }, "密码") },
            confirmButton = { TextButton(onClick = {
                if (action == "disable") { vm.disablePrivacy(password); passwordDialog = null }
                else vm.verifyPrivacyPassword(password) { passwordDialog = null; setup = true }
            }, enabled = !working && password.isNotBlank()) { Text(if (working) "正在验证…" else "确认") } },
            dismissButton = { TextButton(onClick = { passwordDialog = null }) { Text("取消") } })
    }
    if (setup) PatternSetup({ setup = false }) { pattern -> setup = false; vm.setPrivacy(pattern) }
}

@Composable internal fun LocalDataScreen(vm: AppViewModel) {
    var clearHistory by remember { mutableStateOf(false) }
    var enableRetention by remember { mutableStateOf(false) }
    var retentionDialog by remember { mutableStateOf(false) }
    val working = vm.isWorking(Operation.Settings)
    TouchPage("本机数据", vm::back, working) {
        SectionLabel("保留与清理")
        ToggleSetting("本地定时销毁", checked = vm.retentionEnabled, enabled = !working,
            onChange = { if (it) enableRetention = true else vm.enableRetention(false) })
        Text(if (vm.retentionEnabled) "保留最近 ${vm.retentionSeconds / 3600} 小时。过期消息、待发送内容和附件会从本机清理，且不再拉取。服务器及其他账号不受影响。"
            else "已关闭，不按时间清理本机记录。此前已销毁的记录仍不会重新拉取。开启时默认保留 1 小时。", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { retentionDialog = true }, enabled = vm.retentionEnabled && !working) { Text("设置保留时间") }
        SupportingNote("数据库和附件均加密保存，密钥由 Android Keystore 保护。关机、强行停止或系统限制后台时，清理会延后；再次打开前先清理。")
        HorizontalDivider()
        ToggleSetting("本机加密草稿", "默认关闭。开启后仅加密保存文字和引用标识，不保存附件选择；定时销毁同时适用于草稿。", vm.saveDrafts, !working, vm::enableDrafts)
        HorizontalDivider()
        SectionLabel("手动清理")
        Text("清空后，旧消息不会自动重新拉取。服务器和对方的记录不受影响。", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { clearHistory = true }, enabled = !working,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清空本地聊天记录") }
    }
    if (clearHistory) Confirm("清空本地聊天记录", "清除本机已有消息、附件、草稿和待发送内容，旧消息不再自动拉取。保留登录、联系人和设置，不影响服务器及对方记录。", { clearHistory = false }, "清空本地记录", true) { clearHistory = false; vm.clearLocalHistory() }
    if (enableRetention) Confirm("开启本地定时销毁", "默认仅保留最近 1 小时。更早的本机记录与附件将立即清理且不再拉取；服务器和其他账号不受影响。", { enableRetention = false }, "确认并开启", true) { enableRetention = false; vm.enableRetention(true) }
    if (retentionDialog) {
        var hours by remember(vm.retentionSeconds) { mutableStateOf((vm.retentionSeconds / 3600).toString()) }
        val valid = hours.toLongOrNull()?.let { it in 1..8760 } == true
        AlertDialog(onDismissRequest = { retentionDialog = false }, title = { Text("本机保留时间") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1 小时" to 1, "24 小时" to 24, "7 天" to 168).forEach { (label, value) ->
                        FilterChip(selected = hours == value.toString(), onClick = { hours = value.toString() }, label = { Text(label) })
                    }
                }
                OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(4) }, label = { Text("小时（1–8760）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = !valid,
                    supportingText = { if (!valid) Text("请输入 1–8760 之间的小时数") })
                SupportingNote("按消息发送时间计算。缩短后立即清理；延长也不会恢复已销毁的历史。本机截止时间在退出账号后保留，清除应用数据或重装会重置。")
            }
        }, confirmButton = { TextButton(onClick = { vm.setRetention(hours.toLong()); retentionDialog = false }, enabled = !working && valid) { Text("确认并应用") } },
            dismissButton = { TextButton(onClick = { retentionDialog = false }) { Text("取消") } })
    }
}

@Composable internal fun AboutScreen(vm: AppViewModel) {
    TouchPage("关于与更新", vm::back, vm.isWorking(Operation.Update)) {
        Text("Touch", style = MaterialTheme.typography.headlineLarge)
        Text("给此刻，留一点空间。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SupportingNote("版本 ${BuildConfig.VERSION_NAME}")
        SettingsGroup {
            SettingItem("检查更新", if (vm.includePrereleases) "正式版与预发布版本" else "仅正式版", icon = Icons.Outlined.SystemUpdate,
                enabled = !vm.isWorking(Operation.Update)) { vm.checkUpdate() }
            Column(Modifier.padding(start = 60.dp, end = 20.dp, bottom = 8.dp)) {
                ToggleSetting("包含预发布版本", checked = vm.includePrereleases,
                    enabled = !vm.isWorking(Operation.Update), onChange = vm::setPrereleaseUpdates)
            }
            if (vm.updateApk != null && vm.update != null) SettingItem("继续安装已下载的更新", icon = Icons.Outlined.InstallMobile) { vm.showUpdate = true }
            SettingItem("连接诊断", "查看当前连接状态和脱敏诊断信息", Icons.Outlined.NetworkCheck) { vm.diagnostics() }
        }
        SupportingNote("隐私模式仅在解锁后检查更新。可在通知与后台运行中开启提醒；重新打开会补齐离线消息。")
    }
}
