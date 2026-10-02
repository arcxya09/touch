package com.arcxya09.touch

import android.content.pm.ActivityInfo

/** Only the conversation and its attachment viewer opt into the user's portrait lock. */
internal fun screenOrientation(screen: Screen, chatVisible: Boolean, locked: Boolean,
    rotateImages: Boolean, imagePreview: Boolean): Int = when {
    !chatVisible || !locked -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    screen == Screen.Preview && imagePreview && rotateImages -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    screen == Screen.Chat || screen == Screen.Preview -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
