package com.arcxya09.touch.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.Screen
import com.arcxya09.touch.Operation
import com.arcxya09.touch.data.ConnectionStatus

@Composable fun TouchRoot(vm: AppViewModel, activity: MainActivity) {
    TouchTheme {
        val snackbar = remember { SnackbarHostState() }
        ReportDrawnWhen {
            val timerVisible = (!vm.mayShowSession || vm.destination == Screen.Timer) && !(vm.storageError && !vm.privacy)
            if (timerVisible) vm.timerError == null
            else vm.initialized && !vm.storageError && when {
                vm.user == null || vm.user?.mustChange == true || vm.needsPrivacySetup -> true
                vm.destination == Screen.Home -> vm.conversationsLoaded || vm.connectionStatus == ConnectionStatus.RETRYING
                vm.destination == Screen.Chat -> !vm.isWorking(Operation.Conversation)
                else -> true
            }
        }
        // Register the shared handler before page-local unsaved-edit handlers.
        BackHandler(enabled = vm.mayShowChat && vm.user != null && (vm.destination != Screen.Home || vm.privacy)) { vm.back() }
        // No transitions around this gate: a lock replaces every sensitive surface immediately.
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Box(Modifier.weight(1f)) {
                    if (vm.storageError && !vm.privacy) EmptyState("本机数据暂时无法读取", "已停止访问。请重新启动应用；原有数据不会自动清空。")
                    else if (!vm.mayShowSession || vm.destination == Screen.Timer) TimerScreen(vm, activity)
                    else if (vm.user == null) LoginScreen(vm)
                    else if (vm.user!!.mustChange) PasswordScreen(vm, true)
                    else if (vm.needsPrivacySetup) PrivacyWelcome(vm)
                    else when (vm.destination) {
                        Screen.Chat -> ChatScreen(vm, activity)
                        Screen.Contacts -> ContactsScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        Screen.Notifications -> NotificationSettingsScreen(vm, activity)
                        Screen.Profile -> ProfileScreen(vm, activity)
                        Screen.Password -> PasswordScreen(vm, false)
                        Screen.Preview -> PreviewScreen(vm, activity)
                        Screen.PrivacySecurity -> PrivacySecurityScreen(vm, activity)
                        Screen.LocalData -> LocalDataScreen(vm)
                        Screen.About -> AboutScreen(vm)
                        else -> HomeScreen(vm)
                    }
                }
                if (vm.mayShowChat && vm.destination != Screen.Timer) SnackbarHost(snackbar)
            }
        }
        LaunchedEffect(vm.notice, vm.mayShowChat, vm.destination) {
            val notice = vm.notice
            if (notice != null && vm.mayShowChat && vm.destination != Screen.Timer) {
                snackbar.showSnackbar(notice)
                vm.clearNotice()
            } else snackbar.currentSnackbarData?.dismiss()
        }
        LaunchedEffect(vm.safety, vm.initialized) { activity.applySafety() }
        SideEffect { activity.renderedGate() }
        if (vm.mayShowChat && vm.showDiagnostics) DiagnosticsDialog(vm)
        if (vm.mayShowSession) {
            vm.error?.let { message ->
                AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("操作未完成") },
                    text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
                    confirmButton = { TextButton(onClick = { vm.error = null }) { Text("知道了") } })
            }
            if (vm.mayShowChat && vm.user != null && !vm.user!!.mustChange) {
                if (vm.pendingAvatar != null) SelectionConfirm(vm, activity, avatar = true)
                if (vm.pendingSelection != null) SelectionConfirm(vm, activity, avatar = false)
                if (vm.destination != Screen.Timer && vm.showUpdate && vm.update != null) UpdateDialog(vm)
            }
        }
    }
}

@Composable private fun UpdateDialog(vm: AppViewModel) {
    val update = vm.update ?: return
    AlertDialog(onDismissRequest = { if (vm.updateProgress == null) vm.showUpdate = false },
        title = { Text("Touch ${update.versionName}") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SupportingNote("${"%.1f".format(update.apkSize / 1048576.0)} MiB")
                Text(update.changelog)
                vm.updateProgress?.let { progress ->
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("正在下载 · ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                if (vm.updateApk != null) SupportingNote("安装包已校验。授权安装后请返回此处继续安装。")
            }
        }, confirmButton = {
            if (vm.updateProgress == null) TextButton(onClick = {
                if (vm.updateApk == null) vm.downloadUpdate() else vm.installUpdate()
            }) { Text(if (vm.updateApk == null) "下载更新" else "安装更新") }
        }, dismissButton = { TextButton(onClick = { vm.cancelUpdate(); vm.showUpdate = false }) {
            Text(if (vm.updateProgress == null) "稍后" else "取消")
        } })
}
