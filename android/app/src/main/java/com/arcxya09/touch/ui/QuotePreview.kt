package com.arcxya09.touch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.data.*
import kotlinx.coroutines.CancellationException

@Composable internal fun QuotePreview(ref: ReplyRef, cid: String, vm: AppViewModel, clickable: Boolean = true) {
    val clear = vm.conversations.firstOrNull { it.id == cid }?.clearSeq ?: 0
    val permitted = ref.createdAt > vm.visibilityFloor && ref.seq > clear
    var original by remember(ref, cid) { mutableStateOf<ChatMessage?>(null) }
    var status by remember(ref, cid) { mutableStateOf("加载引用…") }
    LaunchedEffect(ref, cid, permitted, clear) {
        original = null
        if (!permitted) { status = "原消息不可用"; return@LaunchedEffect }
        try {
            original = vm.repository.original(cid, ref)
            status = if (original == null) "原消息不可用" else ""
        } catch (e: Exception) { if (e is CancellationException) throw e; status = "引用暂无法加载" }
    }
    Column(Modifier.fillMaxWidth().then(if (clickable && permitted) Modifier.clickable { vm.locate(ref) } else Modifier).padding(bottom = 8.dp)) {
        val message = original.takeIf { permitted }
        if (message == null) Text(if (!permitted) "原消息不可用" else status, style = MaterialTheme.typography.bodySmall)
        else {
            Text(if (message.senderId == vm.user?.id) vm.user?.name.orEmpty() else vm.conversations.firstOrNull { it.id == cid }?.peer?.name.orEmpty(), style = MaterialTheme.typography.labelSmall)
            Text(when (message.kind) { "image" -> "[图片]"; "file" -> "[文件] ${message.file?.name.orEmpty().take(60)}"; else -> message.text.take(120) },
                maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}
