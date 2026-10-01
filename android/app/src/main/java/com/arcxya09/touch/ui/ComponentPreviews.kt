package com.arcxya09.touch.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.timer.TimerState

// Offline, synthetic examples: previews never instantiate a repository or show account data.
@Preview(name = "01 · Timer · Light", widthDp = 360, heightDp = 800, showBackground = true)
@Composable private fun TimerLightPreview() {
    TouchTheme(darkTheme = false) { Surface { TimerContent(TimerState(25, 5, false, false, 25 * 60000L, false), {}, {}, {}, {}) } }
}
@Preview(name = "02 · Timer · Dark", widthDp = 360, heightDp = 800, showBackground = true)
@Composable private fun TimerDarkPreview() {
    TouchTheme(darkTheme = true) { Surface { TimerContent(TimerState(25, 5, false, true, 18 * 60000L + 32000, false), {}, {}, {}, {}) } }
}
@Preview(name = "03 · Timer · Large text", widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable private fun TimerLargeTextPreview() {
    TouchTheme(darkTheme = false) { Surface { TimerContent(TimerState(25, 5, true, false, 5 * 60000L, false), {}, {}, {}, {}) } }
}
@Preview(name = "04 · Timer · Landscape", widthDp = 800, heightDp = 360)
@Composable private fun TimerLandscapePreview() {
    TouchTheme(darkTheme = false) { Surface { TimerContent(TimerState(25, 5, false, false, 25 * 60000L, false), {}, {}, {}, {}) } }
}
@Preview(name = "05 · Components · Light", widthDp = 400, heightDp = 900)
@Preview(name = "06 · Components · Dark", widthDp = 400, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable private fun ComponentPreview() {
    TouchTheme { Surface { ComponentShowcase() } }
}

@Composable internal fun ComponentShowcase() {
    var checked by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TouchHeader("组件样例", back = {})
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("安静，也清晰。", style = MaterialTheme.typography.headlineMedium)
            SupportingNote("以下内容为离线合成示例。")
            SectionLabel("常用设置")
            SettingsGroup { SettingItem("隐私与安全", "隐私模式已开启", Icons.Outlined.Shield) {} }
            ToggleSetting("本机加密草稿", "仅在本机加密保存文字与引用标识。", checked, onChange = { checked = it })
            OutlinedTextField("示例", {}, Modifier.fillMaxWidth(), label = { Text("昵称") }, singleLine = true)
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("保存资料") }
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Text("今天也有值得记录的小事。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            EmptyState("还没有会话", "添加联系人，开始第一段对话。", actionLabel = "添加联系人", action = {})
        }
    }
}
