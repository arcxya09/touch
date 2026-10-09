package com.arcxya09.touch.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.Operation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SelectionDetails(val name: String, val size: Long?, val bitmap: Bitmap?)

@Composable internal fun SelectionConfirm(vm: AppViewModel, activity: MainActivity, avatar: Boolean) {
    val uri = (if (avatar) vm.pendingAvatar else vm.pendingSelection?.first) ?: return
    val isImage = avatar || vm.pendingSelection?.second == "image"
    var details by remember(uri) { mutableStateOf<SelectionDetails?>(null) }
    LaunchedEffect(uri) {
        details = withContext(Dispatchers.IO) {
            var name = if (isImage) "已选择的图片" else "已选择的文件"
            var size: Long? = null
            runCatching { activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            } }
            val image = if (isImage) runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri)) { decoder, info, _ ->
                    decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) / 480).coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull() else null
            SelectionDetails(name, size, image)
        }
    }
    val dismiss = { if (avatar) vm.pendingAvatar = null else vm.pendingSelection = null }
    val working = vm.isWorking(if (avatar) Operation.Profile else Operation.Attachment)
    SelectionConfirmationDialog(avatar, working, details != null, dismiss, if (avatar) vm::uploadAvatar else vm::sendSelection,
        cancel = { vm.cancelSelection(avatar) }) {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            details?.bitmap?.let { bitmap ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Image(bitmap.asImageBitmap(), "所选图片预览", Modifier.fillMaxWidth().height(180.dp), contentScale = if (avatar) ContentScale.Crop else ContentScale.Fit)
                }
            }
            Text(details?.name ?: "正在读取所选内容…", maxLines = 3, overflow = TextOverflow.Ellipsis)
            details?.size?.let { SupportingNote(formatFileSize(it)) }
            SupportingNote(if (avatar) "头像会裁剪为方形，并显示给其他用户。" else "确认后发送到当前会话。")
        }
    }
}

@Composable internal fun SelectionConfirmationDialog(avatar: Boolean, working: Boolean, ready: Boolean,
                                                      dismiss: () -> Unit, confirm: () -> Unit, cancel: () -> Unit = dismiss,
                                                      content: @Composable () -> Unit) {
    AlertDialog(onDismissRequest = { if (!working) dismiss() },
        properties = DialogProperties(dismissOnBackPress = !working, dismissOnClickOutside = !working),
        title = { Text(if (avatar) "更换头像" else "发送附件") }, text = content,
        confirmButton = { TextButton(onClick = confirm, enabled = !working && ready) { Text(if (avatar) "上传头像" else "发送") } },
        dismissButton = { TextButton(onClick = cancel) { Text(if (working) "取消上传" else "取消") } })
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}
