package com.arcxya09.touch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.Operation
import com.arcxya09.touch.Screen
import com.arcxya09.touch.security.Pattern

@Composable internal fun LoginScreen(vm: AppViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val working = vm.isWorking(Operation.Session)
    val focus = LocalFocusManager.current
    val submit = { focus.clearFocus(); vm.login(username.trim(), password) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("TOUCH", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("欢迎回来", style = MaterialTheme.typography.headlineLarge)
                Text("与重要的人，保持联系。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.credentialRecoveryRequired) Text("登录信息需要恢复，本机内容已保留。请使用原账号重新登录。", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(username, { username = it.take(32) }, Modifier.fillMaxWidth(), label = { Text("账号") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next))
            PasswordField(password, { password = it }, "密码", imeAction = ImeAction.Done,
                onDone = { if (!working && username.isNotBlank() && password.isNotEmpty()) submit() })
            Button(onClick = submit, enabled = !working && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (working) "正在登录…" else "登录") }
            SupportingNote("账号由管理员创建。如需账号或重置密码，请联系管理员。")
            TextButton(onClick = { vm.navigate(Screen.Timer) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("使用番茄钟") }
        }
    }
}

@Composable internal fun PasswordField(value: String, onChange: (String) -> Unit, label: String,
    error: String? = null, imeAction: ImeAction = ImeAction.Next, onDone: (() -> Unit)? = null) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, { onChange(it.take(128)) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏密码" else "显示密码") } },
        isError = error != null, supportingText = error?.let { { Text(it) } })
}

@Composable internal fun PasswordScreen(vm: AppViewModel, forced: Boolean) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    val working = vm.isWorking(Operation.Session)
    val error = when { new.isEmpty() -> null; new.length < 10 -> "请至少输入 10 位字符"; new == old -> "新密码不能与当前密码相同"; else -> null }
    val mismatch = if (confirm.isNotEmpty() && new != confirm) "两次输入的密码不一致" else null
    val changed = old.isNotEmpty() || new.isNotEmpty() || confirm.isNotEmpty()
    val back = { if (changed) discard = true else vm.back() }
    BackHandler(enabled = !forced && changed) { discard = true }
    TouchPage(if (forced) "设置新密码" else "修改密码", if (forced) null else back, working) {
        Text(if (forced) "首次登录，请将临时密码改为个人密码。" else "设置一个不易猜测的密码。", style = MaterialTheme.typography.titleMedium)
        PasswordField(old, { old = it }, "当前密码")
        PasswordField(new, { new = it }, "新密码", error)
        PasswordField(confirm, { confirm = it }, "再次输入新密码", mismatch, ImeAction.Done)
        SupportingNote("新密码至少 10 位，且不能与当前密码相同。")
        Button(onClick = { vm.password(old, new) }, enabled = !working && new.length >= 10 && new == confirm && new != old && old.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (working) "正在保存…" else "保存密码") }
        if (forced) TextButton(onClick = vm::logout) { Text("退出登录") }
    }
    if (discard) Confirm("放弃密码修改？", "输入的密码不会保存。", { discard = false }, "放弃修改") { discard = false; vm.back() }
}

@Composable internal fun PrivacyWelcome(vm: AppViewModel) {
    var setup by remember { mutableStateOf(true) }
    BackHandler { setup = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text("设置隐私模式", style = MaterialTheme.typography.headlineMedium)
            Text("默认以番茄钟作为入口。设置并试用手势后，只有绘制正确图案才能进入。")
            Button(onClick = { setup = true }, enabled = !vm.isWorking(Operation.Session), modifier = Modifier.fillMaxWidth()) { Text("设置解锁图案") }
            TextButton(onClick = vm::skipPrivacySetup, enabled = !vm.isWorking(Operation.Session)) { Text("跳过设置，使用正常模式") }
        }
    }
    if (setup && !vm.isWorking(Operation.Session)) PatternSetup({ setup = false }) { pattern -> setup = false; vm.setPrivacy(pattern) }
}

@Composable internal fun PatternSetup(dismiss: () -> Unit, save: (String) -> Unit) {
    var stage by remember { mutableIntStateOf(0) }
    var pattern by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("请至少连接 4 个不同点") }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${stage + 1} / 3", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(listOf("绘制图案", "再次确认", "隐藏图案试解锁")[stage], style = MaterialTheme.typography.headlineSmall)
                Text(hint, style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                    if (stage == 2) Text("25:00", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PatternPad(Modifier.fillMaxSize().testTag("setup-pattern"), stage < 2) { points ->
                        when {
                            !Pattern.valid(points) -> hint = "请至少连接 4 个不同点"
                            stage == 0 -> { pattern = Pattern.encode(points); stage = 1; hint = "按相同顺序再次绘制" }
                            Pattern.encode(points) != pattern -> hint = "图案不一致，请重试"
                            stage == 1 -> { stage = 2; hint = "点位仍在相同位置，这次不显示点和轨迹" }
                            else -> save(pattern)
                        }
                    }
                }
                SupportingNote("计时盘的上、中、下各有三个点位。跨过中点时会自动选中。")
                TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End)) { Text("取消") }
            }
        }
    }
}
