package com.arcxya09.touch.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

@Composable fun PreviewScreen(vm: AppViewModel, activity: MainActivity) {
    val preview = vm.preview ?: return
    val (item, file) = preview
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen = "chat" }) { Text("返回") }
            Text(item.name, Modifier.weight(1f), maxLines = 1, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                item.kind == "image" || item.mime.startsWith("image/") -> BitmapPreview(file)
                item.mime == "application/pdf" || item.name.endsWith(".pdf", true) -> PdfPreview(file)
                item.mime.startsWith("text/") || item.name.endsWith(".txt", true) -> TextPreview(file)
                else -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("使用其他应用查看", style = MaterialTheme.typography.titleLarge)
                    Text("此格式暂不支持内置预览，可打开或保存到你选择的位置。", Modifier.padding(top = 16.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { confirm = "open" }, modifier = Modifier.weight(1f)) { Text("外部打开") }
            Button(onClick = { confirm = "save" }, modifier = Modifier.weight(1f)) { Text("保存文件") }
        }
    }
    confirm?.let { action -> Confirm("文件隐私提示", "外部应用及导出文件不受 Touch 的隐私锁保护，可能保留历史、缓存或副本。", { confirm = null }) {
        confirm = null
        if (action == "open") activity.openExternal(file, item.mime) else activity.export(file, item.name)
    } }
}

@Composable private fun ZoomImage(bitmap: Bitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val gestures = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Image(bitmap.asImageBitmap(), "文件预览", Modifier.fillMaxSize().transformable(gestures).graphicsLayer {
        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
    })
}

@Composable private fun BitmapPreview(file: File) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "图片格式不支持或已损坏" }
                var sample = 1
                while (options.outWidth / sample > 2048 || options.outHeight / sample > 2048) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片无法解码")
            }
        } catch (e: Exception) { error = e.message ?: "图片无法显示" }
    }
    if (error != null) Text(error!!, Modifier.padding(24.dp)) else bitmap?.let { ZoomImage(it) } ?: CircularProgressIndicator()
}

@Composable private fun PdfPreview(file: File) {
    var page by remember(file) { mutableIntStateOf(0) }
    var count by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file, page) {
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    val total = renderer.pageCount
                    require(total > 0) { "PDF 没有可显示的页面" }
                    renderer.openPage(page.coerceIn(0, total - 1)).use { pdfPage ->
                        val factor = min(1600.0 / pdfPage.width, sqrt(4_000_000.0 / (pdfPage.width.toDouble() * pdfPage.height)))
                        val image = Bitmap.createBitmap((pdfPage.width * factor).toInt().coerceAtLeast(1), (pdfPage.height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        image.eraseColor(android.graphics.Color.WHITE)
                        pdfPage.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        image to total
                    }
                }
            }
            bitmap = result.first; count = result.second
        } catch (_: SecurityException) { error = "此 PDF 已加密，请使用支持密码的外部应用打开" }
        catch (e: Exception) { error = "PDF 已损坏或无法预览" }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (error != null) Text(error!!, Modifier.padding(24.dp)) else bitmap?.let { ZoomImage(it) } ?: CircularProgressIndicator()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text("上一页") }
            Text(if (count > 0) "${page + 1} / $count" else "…")
            TextButton(onClick = { page++ }, enabled = page + 1 < count) { Text("下一页") }
        }
    }
}

@Composable private fun TextPreview(file: File) {
    var text by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        text = withContext(Dispatchers.IO) {
            if (file.length() > 2 * 1024 * 1024) "文本超过 2 MiB，请使用外部应用查看。"
            else runCatching {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(file.readBytes())).toString()
            }.getOrDefault("无法按 UTF-8 显示，请使用外部应用查看。")
        }
    }
    if (text == null) CircularProgressIndicator()
    else Text(text!!, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp))
}
