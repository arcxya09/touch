package com.arcxya09.touch

import android.content.pm.ActivityInfo.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOrientationTest {
    @Test fun lockAppliesToEveryPageIncludingLockedAndSignedOutScreens() {
        for (screen in Screen.entries) {
            assertEquals(screen.route, SCREEN_ORIENTATION_PORTRAIT, screenOrientation(screen, true, true, false, false))
            assertEquals(screen.route, SCREEN_ORIENTATION_PORTRAIT, screenOrientation(screen, false, true, true, true))
        }
    }
    @Test fun disablingLockRestoresSystemPolicyForEveryPage() {
        for (screen in Screen.entries) {
            for (visible in listOf(false, true)) for (rotateImages in listOf(false, true)) for (image in listOf(false, true)) {
                assertEquals(screen.route, SCREEN_ORIENTATION_UNSPECIFIED,
                    screenOrientation(screen, visible, false, rotateImages, image))
            }
        }
    }
    @Test fun imageExceptionNeverUnlocksDocumentsOrOtherPages() {
        assertEquals(SCREEN_ORIENTATION_FULL_USER, screenOrientation(Screen.Preview, true, true, true, true))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, screenOrientation(Screen.Preview, true, true, true, false))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, screenOrientation(Screen.Preview, true, true, false, true))
        for (screen in Screen.entries.filter { it != Screen.Preview }) {
            assertEquals(screen.route, SCREEN_ORIENTATION_PORTRAIT, screenOrientation(screen, true, true, true, true))
        }
    }
}
