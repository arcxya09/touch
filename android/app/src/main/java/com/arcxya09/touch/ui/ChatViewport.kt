package com.arcxya09.touch.ui

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*

/** Follow the bottom through IME/composer resizing without pulling readers out of history. */
@Composable internal fun rememberChatAtBottom(scroll: LazyListState, browsingHistory: Boolean): State<Boolean> {
    val atBottom = remember(scroll) { mutableStateOf(true) }
    val history by rememberUpdatedState(browsingHistory)
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(scroll) {
        var previousHeight = 0
        snapshotFlow { scroll.layoutInfo.viewportSize.height to !scroll.canScrollForward }
            .collect { (height, bottom) ->
                val resized = previousHeight > 0 && height > 0 && height != previousHeight
                previousHeight = height
                if (resized && atBottom.value && !history && !dragging && scroll.layoutInfo.totalItemsCount > 0) {
                    // The insets already animate the viewport. A second scroll animation would lag behind it.
                    scroll.scrollToItem(scroll.layoutInfo.totalItemsCount - 1)
                    atBottom.value = !scroll.canScrollForward
                } else atBottom.value = bottom
            }
    }
    return atBottom
}
