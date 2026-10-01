package com.arcxya09.touch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.Operation
import com.arcxya09.touch.notifications.AlertMode

@Composable fun NotificationSettingsScreen(vm: AppViewModel, activity: MainActivity) {
    var confirmContent by remember { mutableStateOf(false) }
    val options = vm.alertOptions
    val working = vm.isWorking(Operation.Settings)
    TouchPage("通知与后台运行", vm::back, working) {
        ToggleSetting("消息通知", "默认关闭。每次开启默认使用隐蔽提醒；打开聊天时不再重复通知。", options.enabled, !working) {
            if (it) activity.enableMessageNotifications() else vm.enableAlerts(false)
        }
        SectionLabel("提醒方式")
        Column(Modifier.selectableGroup()) {
            AlertModeRow("隐蔽提醒（默认）", "只提示已经专注一段时间，记得休息；不显示发送者或消息内容。",
                options.mode == AlertMode.DISCREET, options.enabled && !working) { vm.alertMode(AlertMode.DISCREET) }
            AlertModeRow("真实信息", "显示发送者和文字摘要；图片、文件只显示类型。锁屏时改为通用休息提示。",
                options.mode == AlertMode.CONTENT, options.enabled && !working) { confirmContent = true }
        }
        HorizontalDivider()
        SectionLabel("后台提醒")
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when {
                    !options.enabled -> "尚未开启"
                    vm.backgroundAlerts -> "服务运行中"
                    options.paused -> "已暂停，可使用快捷开关恢复"
                    else -> "暂未运行，请检查通知权限或重新打开应用"
                }, style = MaterialTheme.typography.titleMedium)
                if (options.enabled) OutlinedButton(onClick = { vm.pauseAlerts(vm.backgroundAlerts) }, enabled = !working) { Text(if (vm.backgroundAlerts) "暂停后台提醒" else "恢复后台提醒") }
                SupportingNote("开启后显示一条番茄钟常驻通知，用于后台接收。划掉应用任务后仍可继续；快捷开关或常驻通知可以暂停。暂停不影响前台聊天。")
            }
        }
        SettingsGroup {
            SettingItem("添加控制中心快捷开关") { activity.addReminderTile() }
            SettingItem("系统通知设置") { activity.openNotificationSettings() }
            SettingItem("后台电池设置") { activity.openBatterySettings() }
        }
        SupportingNote("请允许后台运行与自启动，并按需取消电池优化。强行停止、系统停止或部分厂商省电限制会中断接收，需重新打开应用。快捷开关无法绕过系统限制。")
        SupportingNote("隐蔽提醒无声音、振动和角标。系统可以覆盖通知渠道设置，请在系统通知设置中确认。开启真实信息后，系统通知历史或已获通知访问权限的应用可能保留内容。")
    }
    if (confirmContent) Confirm("显示真实信息", "解锁手机时，系统通知会显示发送者和消息摘要，包括应用处于隐私模式时。点击通知仍须通过应用隐私锁。", { confirmContent = false }, "启用真实信息") {
        confirmContent = false; vm.alertMode(AlertMode.CONTENT)
    }
}

@Composable private fun AlertModeRow(title: String, description: String, selected: Boolean, enabled: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled, Role.RadioButton, onClick = select).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SupportingNote(description)
        }
    }
}
