package com.arcxya09.touch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun ChatHeader(title: String, back: () -> Unit, hide: () -> Unit, menu: @Composable () -> Unit) {
    TouchHeader(title, back) {
        IconButton(onClick = hide) { Icon(Icons.Outlined.Timer, "返回番茄钟") }
        menu()
    }
}

@Composable internal fun ChatComposer(draft: String, sending: Boolean, canSend: Boolean,
    edit: (String) -> Unit, send: () -> Unit, attach: () -> Unit, quote: @Composable ColumnScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth()) {
            quote()
            if (!canSend) SupportingNote("当前无法发送，请先建立有效联系人关系。", Modifier.padding(20.dp))
            else Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, edit, Modifier.weight(1f), placeholder = { Text("输入消息") },
                    leadingIcon = { IconButton(onClick = attach, enabled = !sending) { Icon(Icons.Outlined.Add, "添加图片或文件") } },
                    maxLines = 5, shape = MaterialTheme.shapes.large)
                IconButton(onClick = send, enabled = draft.isNotBlank() && !sending) {
                    Icon(Icons.Outlined.Send, "发送", tint = if (draft.isNotBlank() && !sending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
            }
        }
    }
}

@Composable internal fun JumpToLatest(atBottom: Boolean, hasIncoming: Boolean, loading: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (!atBottom || hasIncoming) Surface(onClick = onClick, enabled = !loading,
        modifier = modifier.padding(16.dp).size(48.dp).testTag("jump-to-latest"),
        shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 2.dp) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.KeyboardArrowDown, if (hasIncoming) "有新消息，返回最新" else "返回最新消息")
        }
    }
}

@Composable internal fun ChatBubbleSurface(own: Boolean, highlighted: Boolean = false, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (own) 18.dp else 6.dp, bottomEnd = if (own) 6.dp else 18.dp),
        color = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (own) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier, content = content)
}

/** The popup anchor stays outside the spaced content so opening it cannot resize the bubble. */
@Composable internal fun MessageBubbleFrame(own: Boolean, highlighted: Boolean = false,
    modifier: Modifier = Modifier, menu: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Box {
        ChatBubbleSurface(own, highlighted, modifier) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
        menu()
    }
}

@Composable internal fun MessageActionsMenu(expanded: Boolean, onDismiss: () -> Unit,
    valid: Boolean, recalled: Boolean, isText: Boolean, own: Boolean, busy: Boolean,
    onQuote: () -> Unit, onCopy: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit, onRecall: () -> Unit) {
    DropdownMenu(expanded, onDismiss) {
        if (valid && !recalled) DropdownMenuItem(text = { Text("引用回复") }, onClick = onQuote)
        if (isText) {
            DropdownMenuItem(text = { Text("复制全文") }, onClick = onCopy)
            DropdownMenuItem(text = { Text("选择文字") }, onClick = onSelect)
        }
        DropdownMenuItem(text = { Text("本地删除") }, enabled = valid && !busy, onClick = onDelete)
        if (own && valid && !recalled) DropdownMenuItem(text = { Text("撤回消息") }, enabled = !busy, onClick = onRecall)
    }
}

@Composable internal fun PendingMessageActions(sending: Boolean, busy: Boolean, retry: () -> Unit, discard: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (sending) "发送中" else "发送失败", style = MaterialTheme.typography.labelSmall,
            color = if (sending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        TextButton(onClick = retry, enabled = !sending && !busy) { Text("重试") }
        TextButton(onClick = discard, enabled = !busy) { Text("删除") }
    }
}
