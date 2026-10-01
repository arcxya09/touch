package com.arcxya09.touch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun ChatHeader(title: String, back: () -> Unit, hide: () -> Unit, menu: @Composable () -> Unit) {
    TouchHeader(title, back) {
        IconButton(onClick = hide) { Icon(Icons.Outlined.Timer, "返回番茄钟") }
        menu()
    }
}

@Composable internal fun ChatComposer(draft: String, sending: Boolean, canSend: Boolean,
    edit: (String) -> Unit, send: () -> Unit, attach: () -> Unit, quote: @Composable ColumnScope.() -> Unit = {}) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth()) {
            quote()
            if (!canSend) SupportingNote("当前无法发送，请先建立有效联系人关系。", Modifier.padding(20.dp))
            else Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = attach, enabled = !sending) { Icon(Icons.Outlined.AddCircleOutline, "添加图片或文件") }
                OutlinedTextField(draft, edit, Modifier.weight(1f), placeholder = { Text("输入消息") }, maxLines = 5, shape = MaterialTheme.shapes.large)
                IconButton(onClick = send, enabled = draft.isNotBlank() && !sending) {
                    Icon(Icons.Outlined.Send, "发送", tint = if (draft.isNotBlank() && !sending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
            }
        }
    }
}

@Composable internal fun ChatBubbleSurface(own: Boolean, highlighted: Boolean = false, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (own) 18.dp else 6.dp, bottomEnd = if (own) 6.dp else 18.dp),
        color = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (own) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier, content = content)
}

@Composable internal fun PendingMessageActions(sending: Boolean, busy: Boolean, retry: () -> Unit, discard: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (sending) "发送中" else "发送失败", style = MaterialTheme.typography.labelSmall,
            color = if (sending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        TextButton(onClick = retry, enabled = !sending && !busy) { Text("重试") }
        TextButton(onClick = discard, enabled = !busy) { Text("删除") }
    }
}
