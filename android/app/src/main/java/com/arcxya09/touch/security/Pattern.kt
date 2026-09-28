package com.arcxya09.touch.security

import kotlin.math.abs

object Pattern {
    /** Same midpoint behavior as Android's pattern lock; points are numbered 0..8. */
    fun append(existing: List<Int>, next: Int): List<Int> {
        if (next !in 0..8 || next in existing) return existing
        if (existing.isEmpty()) return listOf(next)
        val previous = existing.last()
        val dr = next / 3 - previous / 3
        val dc = next % 3 - previous % 3
        val crosses = (abs(dr) == 2 && dc == 0) || (abs(dc) == 2 && dr == 0) || (abs(dr) == 2 && abs(dc) == 2)
        val midpoint = (previous + next) / 2
        return existing + (if (crosses && midpoint !in existing) listOf(midpoint) else emptyList()) + next
    }
    fun encode(points: List<Int>) = points.joinToString("")
    fun valid(points: List<Int>) = points.size in 4..9 && points.distinct().size == points.size && points.all { it in 0..8 }
}
