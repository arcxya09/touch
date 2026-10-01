package com.arcxya09.touch.ui

import android.Manifest
import android.app.AlarmManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.MainActivity

@Composable fun TimerScreen(vm: AppViewModel, activity: MainActivity) {
    val timer = vm.timer
    var configure by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(Modifier.fillMaxSize().testTag("timer-screen").padding(horizontal = 24.dp)) {
        val landscape = maxWidth > maxHeight
        val diameter = if (landscape) minOf(maxWidth * 0.42f, (maxHeight - 88.dp).coerceAtLeast(160.dp), 320.dp)
            else minOf(maxWidth, 360.dp)
        val dial: @Composable () -> Unit = {
            Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                    drawArc(faint, -90f, 360f, false, size = Size(size.width, size.height), style = Stroke(5f))
                    drawArc(primary, -90f, 360f * (timer.remainingMs.toFloat() / timer.durationMs).coerceIn(0f, 1f), false,
                        size = Size(size.width, size.height), style = Stroke(8f, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val seconds = (timer.remainingMs + 999) / 1000
                    Text("%02d:%02d".format(seconds / 60, seconds % 60), fontSize = (diameter.value / 5.5f).coerceIn(32f, 58f).sp, fontWeight = FontWeight.Light)
                    Text(if (timer.complete) "已完成" else if (timer.resting) "休息" else "专注", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (vm.privacy && vm.initialized) PatternPad(Modifier.fillMaxSize().testTag("hidden-pattern"), false, vm::unlock)
            }
        }
        val controls: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (timer.resting) "留一点时间给自己" else "此刻，只做一件事", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = !timer.resting, onClick = { vm.timerChoose(false) }, label = { Text("专注") })
                    FilterChip(selected = timer.resting, onClick = { vm.timerChoose(true) }, label = { Text("休息") })
                }
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::timerReset, shape = CircleShape) { Text("重置") }
                    Button(onClick = { if (timer.running) vm.timerPause() else { vm.timerStart(); activity.notificationPermission() } },
                        contentPadding = PaddingValues(36.dp, 16.dp), shape = CircleShape) { Text(if (timer.running) "暂停" else "开始") }
                }
                val exact = Build.VERSION.SDK_INT < 31 || activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                if (!exact) TextButton(onClick = activity::exactAlarmPermission) { Text("允许准时提醒", style = MaterialTheme.typography.labelMedium) }
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    TextButton(onClick = activity::notificationPermission) { Text("通知未开启，后台提醒无法显示", style = MaterialTheme.typography.labelMedium) }
                }
                Text("慢慢来，也是在向前。", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Touch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (!vm.privacy && vm.initialized) TextButton(onClick = vm::returnFromTimer) { Text("返回") }
                TextButton(onClick = { configure = true }) { Text("时长") }
            }
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { dial() }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { controls() }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(20.dp))
                    dial()
                    Spacer(Modifier.height(24.dp))
                    controls()
                }
            }
        }
    }
    if (configure) {
        var focus by remember { mutableStateOf(timer.focusMinutes.toString()) }
        var rest by remember { mutableStateOf(timer.restMinutes.toString()) }
        AlertDialog(onDismissRequest = { configure = false }, title = { Text("计时时长") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(focus, { focus = it.filter(Char::isDigit).take(3) }, label = { Text("专注分钟（1—180）") })
                OutlinedTextField(rest, { rest = it.filter(Char::isDigit).take(2) }, label = { Text("休息分钟（1—60）") })
                Text("修改时长会重置当前计时。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { vm.timerConfigure(focus.toInt(), rest.toInt()); configure = false },
            enabled = focus.toIntOrNull() in 1..180 && rest.toIntOrNull() in 1..60) { Text("保存") } }, dismissButton = { TextButton(onClick = { configure = false }) { Text("取消") } })
    }
}
