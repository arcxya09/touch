package com.arcxya09.touch.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.data.ChatMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val thumbnailSlots = Semaphore(2)

@Composable internal fun ChatImagePreview(message: ChatMessage, vm: AppViewModel) {
    var bitmap by remember(message.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id) {
        try {
            bitmap = thumbnailSlots.withPermit {
                check(vm.mayShowChat && vm.screen == "chat")
                val file = vm.repository.download(message) {}
                withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    file.input().use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                    val result = file.input().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    file.checkAccess()
                    requireNotNull(result)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Box(Modifier.size(240.dp, 180.dp), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "图片预览，点击查看", Modifier.fillMaxSize().testTag("thumbnail-${message.id}"), contentScale = ContentScale.Fit) }
            ?: if (failed) Text("预览加载失败，点击查看", style = MaterialTheme.typography.labelSmall)
            else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}
