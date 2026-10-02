package com.arcxya09.touch

import android.content.pm.ActivityInfo.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOrientationTest {
    @Test fun lockAppliesOnlyToConversationAndAttachments() {
        for (screen in Screen.entries) {
            assertEquals(if (screen == Screen.Chat || screen == Screen.Preview) SCREEN_ORIENTATION_PORTRAIT
                else SCREEN_ORIENTATION_UNSPECIFIED, screenOrientation(screen, true, true, false, false))
            assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, screenOrientation(screen, false, true, true, true))
            assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, screenOrientation(screen, true, false, true, true))
        }
    }
    @Test fun imageExceptionNeverUnlocksDocumentsOrConversation() {
        assertEquals(SCREEN_ORIENTATION_FULL_USER, screenOrientation(Screen.Preview, true, true, true, true))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, screenOrientation(Screen.Preview, true, true, true, false))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, screenOrientation(Screen.Preview, true, true, false, true))
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, screenOrientation(Screen.Chat, true, true, true, true))
    }
}
