package com.arcxya09.touch

import com.arcxya09.touch.ui.imagePanLimits
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePanLimitsTest {
    @Test fun unzoomedImageCannotBeDraggedOutOfItsFrame() {
        val limits = imagePanLimits(400, 300, 1200, 300, 1f)
        assertEquals(0f, limits.horizontal, 0.001f)
        assertEquals(0f, limits.vertical, 0.001f)
    }
    @Test fun wideImageOnlyPansBeyondFittedHorizontalEdge() {
        val limits = imagePanLimits(400, 300, 1200, 300, 2f)
        assertEquals(200f, limits.horizontal, 0.001f)
        assertEquals(0f, limits.vertical, 0.001f)
    }
    @Test fun portraitLetterboxCannotBePannedIntoEmptySpace() {
        val limits = imagePanLimits(400, 300, 200, 800, 2f)
        assertEquals(0f, limits.horizontal, 0.001f)
        assertEquals(150f, limits.vertical, 0.001f)
    }
    @Test fun unmeasuredViewportHasNoPanRange() {
        val limits = imagePanLimits(0, 0, 200, 800, 2f)
        assertEquals(0f, limits.horizontal, 0f)
        assertEquals(0f, limits.vertical, 0f)
    }
}
