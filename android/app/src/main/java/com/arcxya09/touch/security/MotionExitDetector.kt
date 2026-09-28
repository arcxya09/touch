package com.arcxya09.touch.security

import kotlin.math.sqrt

/** Foreground-only detector. Timestamps are monotonic milliseconds. */
class MotionExitDetector {
    private var start = -1L
    private var upright = false
    private var downSince = -1L
    private var peaks = 0
    private var firstPeak = 0L
    private var high = false
    private var fired = false
    fun sample(now: Long, x: Float, y: Float, z: Float, flip: Boolean, shake: Boolean): Boolean {
        if (fired) return false
        if (start < 0) start = now
        val g = sqrt(x*x + y*y + z*z) / 9.80665f
        if (z > -3f && g in 0.8f..1.2f) upright = true
        if (now - start < 600) return false
        if (flip && upright && z < -8f && g in 0.8f..1.2f) {
            if (downSince < 0) downSince = now
            if (now - downSince >= 600) fired = true
        } else downSince = -1
        if (now - firstPeak > 1200) peaks = 0
        if (g < 1.4f) high = false
        if (shake && g > 2.7f && !high) {
            high = true
            if (peaks == 0) firstPeak = now
            peaks++
            if (peaks >= 3) fired = true
        }
        return fired
    }
}
