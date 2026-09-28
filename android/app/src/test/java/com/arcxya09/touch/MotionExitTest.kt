package com.arcxya09.touch
import com.arcxya09.touch.security.MotionExitDetector
import org.junit.Assert.*
import org.junit.Test

class MotionExitTest {
    @Test fun faceDownAtStartupAndOrdinaryMovementDoNotExit() {
        val d = MotionExitDetector()
        for (t in 0L..5000L step 20) assertFalse(d.sample(t, 0f, 0f, -9.8f, true, true))
        for (t in 5020L..8000L step 20) assertFalse(d.sample(t, 2f, 3f, 9f, true, true))
    }
    @Test fun flipRequiresStableDownwardOrientationAndOnlyFiresOnce() {
        val d = MotionExitDetector()
        assertFalse(d.sample(0, 0f, 0f, 9.8f, true, false))
        assertFalse(d.sample(700, 0f, 0f, -9.8f, true, false))
        assertFalse(d.sample(1000, 0f, 0f, 0f, true, false))
        assertFalse(d.sample(1100, 0f, 0f, -9.8f, true, false))
        assertFalse(d.sample(1699, 0f, 0f, -9.8f, true, false))
        assertTrue(d.sample(1700, 0f, 0f, -9.8f, true, false))
        assertFalse(d.sample(1800, 0f, 0f, -9.8f, true, false))
    }
    @Test fun shakeRequiresThreeSeparatePeaksInWindow() {
        val d = MotionExitDetector()
        d.sample(0, 0f, 0f, 9.8f, false, true)
        assertFalse(d.sample(700, 30f, 0f, 9.8f, false, true))
        assertFalse(d.sample(750, 30f, 0f, 9.8f, false, true))
        d.sample(800, 0f, 0f, 9.8f, false, true)
        assertFalse(d.sample(1000, -30f, 0f, 9.8f, false, true))
        d.sample(1100, 0f, 0f, 9.8f, false, true)
        assertTrue(d.sample(1300, 30f, 0f, 9.8f, false, true))
    }
    @Test fun disabledSettingsAndDistantShocksDoNotExit() {
        val d = MotionExitDetector()
        d.sample(0, 0f, 0f, 9.8f, false, true)
        for (t in 2000L..10000L step 2000) {
            assertFalse(d.sample(t, 30f, 0f, 9.8f, false, true))
            assertFalse(d.sample(t + 100, 0f, 0f, 9.8f, false, true))
        }
        for (t in 11000L..14000L step 20) assertFalse(d.sample(t, 0f, 0f, -9.8f, false, false))
    }
}
