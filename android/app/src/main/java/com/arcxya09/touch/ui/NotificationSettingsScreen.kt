package com.arcxya09.touch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.notifications.AlertMode

@Composable fun NotificationSettingsScreen(vm: AppViewModel, activity: MainActivity) {
    var confirmContent by remember { mutableStateOf(false) }
    val options = vm.alertOptions
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = { vm.screen = "settings" }) { Text("返回设置") }
        Text("通知与后台运行", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("消息通知", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(options.enabled, { if (it) activity.enableMessageNotifications() else vm.enableAlerts(false) },
                enabled = !vm.busy, modifier = Modifier.semantics { contentDescription = "消息通知开关" })
        }
        Text("默认关闭。每次开启默认使用隐蔽提醒；打开聊天时不再重复通知。")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(options.mode == AlertMode.DISCREET, { vm.alertMode(AlertMode.DISCREET) }, enabled = options.enabled && !vm.busy)
            Column { Text("隐蔽提醒（默认）"); Text("只提示已经专注一段时间，记得休息；不显示发送者或消息内容。", style = MaterialTheme.typography.bodySmall) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(options.mode == AlertMode.CONTENT, { confirmContent = true }, enabled = options.enabled && !vm.busy)
            Column { Text("真实信息"); Text("显示发送者和文字摘要；图片、文件只显示类型。锁屏时改为通用休息提示。", style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider()
        Text("后台提醒", style = MaterialTheme.typography.titleMedium)
        Text(when {
            !options.enabled -> "尚未开启"
            vm.backgroundAlerts -> "服务运行中"
            options.paused -> "已暂停，可使用快捷开关恢复"
            else -> "暂未运行，请检查通知权限或重新打开应用"
        })
        if (options.enabled) OutlinedButton(onClick = { vm.pauseAlerts(vm.backgroundAlerts) }, enabled = !vm.busy) {
            Text(if (vm.backgroundAlerts) "暂停后台提醒" else "恢复后台提醒")
        }
        Text("开启后显示一条番茄钟常驻通知，用于后台接收。划掉应用任务后仍可继续；快捷开关或常驻通知可以暂停。暂停不影响前台聊天。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = activity::addReminderTile) { Text("添加控制中心快捷开关") }
        OutlinedButton(onClick = activity::openNotificationSettings) { Text("系统通知设置") }
        OutlinedButton(onClick = activity::openBatterySettings) { Text("后台电池设置") }
        Text("请允许后台运行与自启动，并按需取消电池优化。强行停止、系统停止或部分厂商省电限制会中断接收，需重新打开应用。快捷开关无法绕过系统限制。", style = MaterialTheme.typography.bodySmall)
        Text("隐蔽提醒无声音、振动和角标。系统可以覆盖通知渠道设置，请在系统通知设置中确认。开启真实信息后，系统通知历史或已获通知访问权限的应用可能保留内容。", style = MaterialTheme.typography.bodySmall)
    }
    if (confirmContent) AlertDialog(onDismissRequest = { confirmContent = false }, title = { Text("显示真实信息") },
        text = { Text("解锁手机时，系统通知会显示发送者和消息摘要，包括应用处于隐私模式时。点击通知仍须通过应用隐私锁。") },
        confirmButton = { TextButton(onClick = { confirmContent = false; vm.alertMode(AlertMode.CONTENT) }) { Text("启用真实信息") } },
        dismissButton = { TextButton(onClick = { confirmContent = false }) { Text("取消") } })
}
