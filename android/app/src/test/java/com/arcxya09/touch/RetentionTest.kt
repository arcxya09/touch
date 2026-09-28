package com.arcxya09.touch

import com.arcxya09.touch.data.Retention
import org.junit.Assert.*
import org.junit.Test

class RetentionTest {
    @Test fun expiryBoundary() {
        val floor = Retention.cutoff(0, 10000, 3600)
        assertEquals(6400, floor)
        assertFalse(Retention.retained(6400, floor))
        assertTrue(Retention.retained(6401, floor))
    }
    @Test fun extendingWindowAndRollingBackClockCannotResurrectHistory() {
        val floor = Retention.cutoff(0, 10000, 3600)
        assertEquals(floor, Retention.cutoff(floor, 10000, 86400))
        assertEquals(floor, Retention.cutoff(floor, 8000, 3600))
        assertEquals(16400, Retention.cutoff(floor, 20000, 3600))
    }
}
