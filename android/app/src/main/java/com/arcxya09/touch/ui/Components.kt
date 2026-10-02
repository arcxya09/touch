package com.arcxya09.touch.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arcxya09.touch.AppViewModel
import com.arcxya09.touch.data.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun TouchHeader(title: String, back: (() -> Unit)? = null, status: String? = null,
    onStatus: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        Column(Modifier.weight(1f).padding(start = if (back == null) 8.dp else 4.dp)
            .heightIn(min = 48.dp)
            .then(if (status != null && onStatus != null) Modifier.clickable(onClickLabel = "查看连接状态", onClick = onStatus) else Modifier),
            verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            if (status != null) Text(status, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("connection-status"))
        }
        actions()
    }
}

@Composable internal fun TouchPage(title: String, back: (() -> Unit)?, busy: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TouchHeader(title, back)
        BusyIndicator(busy)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        }
    }
}

@Composable internal fun BusyIndicator(busy: Boolean) {
    Box(Modifier.fillMaxWidth().height(4.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxSize())
    }
}

@Composable internal fun EmptyState(title: String, subtitle: String, icon: ImageVector = Icons.Outlined.ChatBubbleOutline,
    actionLabel: String? = null, action: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(icon, null, Modifier.padding(20.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && actionLabel != null) FilledTonalButton(onClick = action) { Text(actionLabel) }
    }
}

@Composable internal fun SupportingNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp).semantics { heading() })
}

@Composable internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable internal fun SettingItem(title: String, subtitle: String? = null, icon: ImageVector? = null,
    destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        if (icon != null) Icon(icon, null, tint = color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) SupportingNote(subtitle)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun ToggleSetting(title: String, description: String? = null, checked: Boolean,
    enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics(mergeDescendants = true) { contentDescription = title + "开关" }.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked, onCheckedChange = null, enabled = enabled)
        }
        if (description != null) SupportingNote(description, Modifier.padding(top = 4.dp, end = 24.dp))
    }
}

@Composable internal fun Avatar(person: Person, vm: AppViewModel, size: Dp = 48.dp) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, person.id, person.avatarVersion, vm.user?.id) {
        value = null
        value = runCatching { vm.repository.avatarBytes(person)?.let { bytes ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        } }.getOrNull()
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().testTag("avatar-${person.id}"), contentScale = ContentScale.Crop) }
                ?: Text(person.name.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable fun Confirm(title: String, text: String, dismiss: () -> Unit,
    confirmLabel: String = "确认", destructive: Boolean = false, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Text(text, Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = {
        TextButton(onClick = confirm, colors = ButtonDefaults.textButtonColors(contentColor =
            if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) { Text(confirmLabel) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
