package com.arcxya09.touch.ui

import kotlin.math.min

internal data class ImagePanLimits(val horizontal: Float, val vertical: Float)

/** Limits use the fitted image bounds, not its surrounding letterbox. */
internal fun imagePanLimits(viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int, scale: Float): ImagePanLimits {
    if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return ImagePanLimits(0f, 0f)
    val fit = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
    return ImagePanLimits(
        ((imageWidth * fit * scale - viewWidth) / 2f).coerceAtLeast(0f),
        ((imageHeight * fit * scale - viewHeight) / 2f).coerceAtLeast(0f),
    )
}
