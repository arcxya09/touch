package com.arcxya09.touch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.notifications.AlertService
import kotlinx.coroutines.launch

@Composable internal fun DiagnosticsDialog(vm: AppViewModel) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val report = remember(vm.expiryTick, vm.diagnosticDetails) { vm.repository.connection.report(AlertService.running) }
    AlertDialog(onDismissRequest = { vm.showDiagnostics = false }, title = { Text("连接诊断") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(report)
            Text("详细记录（仅本次进程，最多 40 条脱敏事件）")
            Switch(checked = vm.diagnosticDetails, onCheckedChange = vm::enableDiagnostics)
        }
    }, confirmButton = { TextButton(onClick = { vm.showDiagnostics = false }) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("连接诊断", report))) } }) { Text("复制脱敏信息") } })
}
