package com.arcxya09.touch

import com.arcxya09.touch.security.Pattern
import org.junit.Assert.*
import org.junit.Test

class PatternTest {
    @Test fun midpointIsInserted() {
        assertEquals(listOf(0, 1, 2), Pattern.append(listOf(0), 2))
        assertEquals(listOf(0, 4, 8), Pattern.append(listOf(0), 8))
        assertEquals(listOf(2, 4, 6), Pattern.append(listOf(2), 6))
    }
    @Test fun repeatsAndInvalidPointsAreIgnored() {
        assertEquals(listOf(0, 1, 2), Pattern.append(listOf(0, 1, 2), 1))
        assertEquals(listOf(0), Pattern.append(listOf(0), 9))
    }
    @Test fun usedMidpointIsNotDuplicated() {
        assertEquals(listOf(4, 0, 8), Pattern.append(listOf(4, 0), 8))
        assertFalse(Pattern.valid(listOf(0, 1, 2)))
        assertTrue(Pattern.valid(listOf(0, 1, 2, 5)))
    }
}
