package com.arcxya09.touch.ui

import android.os.PersistableBundle
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.core.text.util.LinkifyCompat
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration

internal data class MessageLink(val start: Int, val end: Int, val url: String)

internal fun messageLinks(text: String): List<MessageLink> {
    val linked = SpannableString(text)
    LinkifyCompat.addLinks(linked, Linkify.WEB_URLS)
    return linked.getSpans(0, linked.length, URLSpan::class.java).mapNotNull { span ->
        val start = linked.getSpanStart(span)
        var end = (start until linked.getSpanEnd(span)).firstOrNull {
            text[it] in "。，、；：！？（）【】《》「」『』“”‘’"
        } ?: linked.getSpanEnd(span)
        // Prose punctuation is not part of a pasted address. Keep balanced URL parentheses.
        while (end > start && (text[end - 1] in "。，、；：！？,.!?;:）】》」』”’" ||
                (text[end - 1] == ')' && text.substring(start, end).count { it == ')' } > text.substring(start, end).count { it == '(' }))) end--
        val displayed = text.substring(start, end)
        val url = if (displayed.startsWith("http://", true) || displayed.startsWith("https://", true)) displayed
            else if ("://" !in displayed) "https://$displayed" else return@mapNotNull null
        if (end > start) MessageLink(start, end, url) else null
    }
}

/** Native selection handles/menu; annotations keep the copied text identical to the message. */
@Composable internal fun MessageText(text: String, openLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val links = remember(text) { messageLinks(text) }
    val currentOpen by rememberUpdatedState(openLink)
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    val annotated = remember(text, links, style) {
        AnnotatedString.Builder(text).apply {
            links.forEach { link ->
                addStyle(style, link.start, link.end)
            }
        }.toAnnotatedString()
    }
    val clipboard = LocalClipboard.current
    val privateClipboard = remember(clipboard) {
        object : Clipboard by clipboard {
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                clipEntry?.clipData?.description?.let { description ->
                    description.extras = PersistableBundle(description.extras ?: PersistableBundle()).apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                }
                clipboard.setClipEntry(clipEntry)
            }
        }
    }
    CompositionLocalProvider(LocalClipboard provides privateClipboard) {
        SelectionContainer {
            Text(annotated, modifier
                .semantics {
                    customActions = links.map { link ->
                        CustomAccessibilityAction("打开链接 ${link.url}") { currentOpen(link.url); true }
                    }
                }
                .pointerInput(text, links) {
                    // Observe without consuming: SelectionContainer owns long-press and drag.
                    // LinkAnnotation consumes long presses as clicks on some Compose versions.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        var cancelled = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (event.changes.count { it.pressed } > 1 ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                                change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) cancelled = true
                            if (!change.pressed) {
                                if (!cancelled) layout?.let { result ->
                                    val caret = result.getOffsetForPosition(down.position)
                                    val offset = listOf(caret, caret - 1).firstOrNull {
                                        it in text.indices && result.getBoundingBox(it).contains(down.position)
                                    }
                                    if (offset != null) links.firstOrNull { offset in it.start until it.end }?.let { currentOpen(it.url) }
                                }
                                break
                            }
                        } while (true)
                    }
                }, onTextLayout = { layout = it })
        }
    }
}
