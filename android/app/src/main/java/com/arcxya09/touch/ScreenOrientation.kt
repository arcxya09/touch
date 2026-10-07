package com.arcxya09.touch

import android.content.pm.ActivityInfo

/** The portrait lock is global; only a visible image viewer may opt out. */
internal fun screenOrientation(screen: Screen, previewVisible: Boolean, locked: Boolean,
    rotateImages: Boolean, imagePreview: Boolean): Int = when {
    !locked -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    screen == Screen.Preview && previewVisible && imagePreview && rotateImages -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
