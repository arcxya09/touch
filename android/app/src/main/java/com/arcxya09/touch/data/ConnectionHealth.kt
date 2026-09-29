package com.arcxya09.touch.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionStatus(val label: String) {
    CONNECTING("连接中"), SYNCING("同步中"), LIVE("实时连接"), RETRYING("重连中")
}

/** A past HTTP success is not proof that the live connection still exists. */
class ConnectionHealth(private val now: () -> Long) {
    data class State(val generation: Long = 0, val socketOpen: Boolean = false,
        val syncing: Boolean = false, val failed: Boolean = false, val checkedAt: Long? = null,
        val cursor: Long = -1, val requiredCursor: Long = -1)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    @Synchronized fun invalidate() { mutable.value = State(generation = mutable.value.generation + 1, failed = true) }
    @Synchronized fun opening() { mutable.value = mutable.value.copy(failed = false) }
    @Synchronized fun opened() { mutable.value = mutable.value.copy(socketOpen = true) }
    @Synchronized fun event(cursor: Long) { mutable.value = mutable.value.copy(requiredCursor = maxOf(cursor, mutable.value.requiredCursor)) }
    @Synchronized fun begin(): Long {
        mutable.value = mutable.value.copy(syncing = true)
        return mutable.value.generation
    }
    @Synchronized fun success(generation: Long, cursor: Long) {
        if (generation != mutable.value.generation) return
        mutable.value = mutable.value.copy(syncing = false, failed = false, checkedAt = now(), cursor = cursor)
    }
    @Synchronized fun failure(generation: Long) {
        if (generation == mutable.value.generation) mutable.value = mutable.value.copy(syncing = false, failed = true, checkedAt = null)
    }
    fun status(): ConnectionStatus {
        val s = mutable.value
        if (s.failed) return ConnectionStatus.RETRYING
        if (!s.socketOpen) return ConnectionStatus.CONNECTING
        if (s.syncing || s.checkedAt == null || s.cursor < s.requiredCursor) return ConnectionStatus.SYNCING
        if (now() - s.checkedAt !in 0..45000) return ConnectionStatus.RETRYING
        return ConnectionStatus.LIVE
    }
}
