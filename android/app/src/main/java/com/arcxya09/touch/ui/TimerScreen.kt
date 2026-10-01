package com.arcxya09.touch.ui

import android.Manifest
import android.app.AlarmManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.timer.TimerState

@Composable fun TimerScreen(vm: AppViewModel, activity: MainActivity) {
    var configure by remember { mutableStateOf(false) }
    var resetAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val timer = vm.timer
    val choose: (Boolean) -> Unit = { resting ->
        if (resting != timer.resting) {
            if (timer.running) resetAction = { vm.timerChoose(resting) } else vm.timerChoose(resting)
        }
    }
    val exact = Build.VERSION.SDK_INT < 31 || activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    TimerContent(timer, { configure = true },
        { if (timer.running) vm.timerPause() else { vm.timerStart(); activity.notificationPermission() } },
        { if (timer.running) resetAction = vm::timerReset else vm.timerReset() }, choose,
        returnAction = if (!vm.privacy && vm.initialized) vm::returnFromTimer else null,
        privacyOverlay = { if (vm.privacy && vm.initialized) PatternPad(Modifier.fillMaxSize().testTag("hidden-pattern"), false, vm::unlock) },
        permissions = {
            if (!exact || !notifications) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!exact) TextButton(onClick = activity::exactAlarmPermission) { Icon(Icons.Outlined.Alarm, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("允许准时提醒", style = MaterialTheme.typography.labelMedium) }
                    if (!notifications) TextButton(onClick = activity::notificationPermission) { Text("通知未开启，后台提醒无法显示", style = MaterialTheme.typography.labelMedium) }
                }
            }
        })
    if (configure) TimerDurationDialog(timer, { configure = false }) { focus, rest -> vm.timerConfigure(focus, rest); configure = false }
    resetAction?.let { action -> Confirm("重置当前计时？", "正在进行的这一段计时会结束，并回到完整时长。", { resetAction = null }, "重置计时") { resetAction = null; action() } }
    vm.timerError?.let { message ->
        AlertDialog(onDismissRequest = vm::clearTimerError, title = { Text("计时暂未完成") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::clearTimerError) { Text("知道了") } })
    }
}

@Composable internal fun TimerContent(timer: TimerState, configure: () -> Unit, toggle: () -> Unit,
    reset: () -> Unit, choose: (Boolean) -> Unit, returnAction: (() -> Unit)? = null,
    privacyOverlay: @Composable BoxScope.() -> Unit = {}, permissions: @Composable () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("timer-screen")) {
        val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
        val dialSize = if (landscape) minOf(maxWidth * 0.42f, (maxHeight - 100.dp).coerceAtLeast(160.dp), 320.dp) else minOf(maxWidth - 64.dp, 320.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Touch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (returnAction != null) TextButton(onClick = returnAction) { Text("返回") }
                TextButton(onClick = configure) { Text("时长") }
            }
            val dial: @Composable () -> Unit = { TimerDial(timer, dialSize, privacyOverlay) }
            val controls: @Composable () -> Unit = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(if (timer.resting) "留一点时间给自己" else "此刻，只做一件事", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = !timer.resting, onClick = { choose(false) }, label = { Text("专注 · ${timer.focusMinutes} 分钟") })
                        FilterChip(selected = timer.resting, onClick = { choose(true) }, label = { Text("休息 · ${timer.restMinutes} 分钟") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = reset, modifier = Modifier.heightIn(min = 52.dp), shape = CircleShape, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) { Text("重置") }
                        Button(onClick = toggle, modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 148.dp), shape = CircleShape, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)) {
                            Icon(if (timer.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (timer.running) "暂停" else if (timer.complete) "再来一段" else if (timer.remainingMs < timer.durationMs) "继续" else "开始")
                        }
                    }
                    permissions()
                    SupportingNote("慢慢来，也是在向前。", Modifier.padding(bottom = 12.dp))
                }
            }
            if (landscape) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { dial() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { controls() }
            } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Spacer(Modifier.height(24.dp)); dial(); Spacer(Modifier.height(32.dp)); controls()
            }
        }
    }
}

@Composable private fun TimerDial(timer: TimerState, diameter: Dp, overlay: @Composable BoxScope.() -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val seconds = (timer.remainingMs + 999) / 1000
    val phase = if (timer.complete) "已完成" else if (timer.resting) "休息" else "专注"
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(faint.copy(alpha = 0.7f), -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(1.5.dp.toPx()))
            drawArc(primary, -90f, 360f * (timer.remainingMs.toFloat() / timer.durationMs).coerceIn(0f, 1f), false,
                Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$phase，剩余 ${seconds / 60} 分 ${seconds % 60} 秒" }) {
            Text("%02d:%02d".format(seconds / 60, seconds % 60),
                // Convert physical size through Android's nonlinear font scaling. Larger font
                // preferences may enlarge the dial numerals, but must never make them smaller.
                fontSize = with(density) { (diameter.toPx() / 5.6f * fontScale.coerceIn(1f, 1.15f)).toSp() },
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = (-2).sp,
                modifier = Modifier.clearAndSetSemantics {})
            Text(phase, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
        }
        overlay()
    }
}

@Composable private fun TimerDurationDialog(timer: TimerState, dismiss: () -> Unit, save: (Int, Int) -> Unit) {
    var focus by remember { mutableStateOf(timer.focusMinutes.toString()) }
    var rest by remember { mutableStateOf(timer.restMinutes.toString()) }
    val focusValid = focus.toIntOrNull() in 1..180
    val restValid = rest.toIntOrNull() in 1..60
    AlertDialog(onDismissRequest = dismiss, title = { Text("计时时长") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25 to 5, 50 to 10, 90 to 15).forEach { (f, r) ->
                    FilterChip(selected = focus == f.toString() && rest == r.toString(), onClick = { focus = f.toString(); rest = r.toString() }, label = { Text("$f / $r") })
                }
            }
            OutlinedTextField(focus, { focus = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = { Text("专注分钟（1—180）") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !focusValid,
                supportingText = { if (!focusValid) Text("请输入 1–180 之间的分钟数") })
            OutlinedTextField(rest, { rest = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), label = { Text("休息分钟（1—60）") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !restValid,
                supportingText = { if (!restValid) Text("请输入 1–60 之间的分钟数") })
            SupportingNote("修改时长会重置当前计时。")
        }
    }, confirmButton = { TextButton(onClick = { save(focus.toInt(), rest.toInt()) }, enabled = focusValid && restValid) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
