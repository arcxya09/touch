package com.arcxya09.touch.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionStatus(val label: String) {
    CONNECTING("连接中"), SYNCING("同步中"), LIVE("实时连接"), RETRYING("重连中")
}

/** A past HTTP success is not proof that the live connection still exists. */
class ConnectionHealth(private val now: () -> Long) {
    companion object { const val SYNC_FALLBACK_MS = 5 * 60 * 1000L }
    data class State(val generation: Long = 0, val socketOpen: Boolean = false,
        val syncing: Boolean = false, val failed: Boolean = false, val checkedAt: Long? = null,
        val cursor: Long = -1, val requiredCursor: Long = -1)
    @Volatile var lastSyncWall: Long? = null; private set
    @Volatile var reconnects = 0; private set
    @Volatile var failureCategory = "无"; private set
    @Volatile var detailed = false; private set
    private var attempts = 0
    private val records = java.util.ArrayDeque<String>()
    @Synchronized fun detailed(enabled: Boolean) { detailed = enabled; if (!enabled) records.clear() }
    @Synchronized private fun record(event: String) {
        if (detailed) { records.addLast("${System.currentTimeMillis()} $event"); while (records.size > 40) records.removeFirst() }
    }
    @Synchronized fun failedWith(error: Throwable) {
        failureCategory = when (error) {
            is java.net.SocketTimeoutException -> "网络超时"
            is javax.net.ssl.SSLException -> "TLS"
            is java.net.UnknownHostException -> "DNS"
            is ApiException -> if (error.status == 401) "登录失效" else if (error.status >= 500) "服务异常" else "请求被拒绝"
            is java.io.IOException -> "连接中断"
            is kotlinx.coroutines.CancellationException -> "操作取消"
            else -> "本地处理失败"
        }
        record(failureCategory)
    }
    @Synchronized fun report(background: Boolean): String = buildString {
        appendLine("Touch ${com.arcxya09.touch.BuildConfig.VERSION_NAME}")
        appendLine("状态：${status().label}")
        appendLine("最近同步：${lastSyncWall?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(it)) } ?: "暂无"}")
        appendLine("重连次数（本进程）：$reconnects")
        appendLine("最近失败类别：$failureCategory")
        appendLine("后台服务：${if (background) "运行中" else "未运行"}")
        if (detailed) records.forEach { appendLine(it) }
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    @Synchronized fun invalidate() { mutable.value = State(generation = mutable.value.generation + 1, failed = true) }
    @Synchronized fun opening() { if (attempts++ > 0) reconnects++; record("连接开始"); mutable.value = mutable.value.copy(failed = false) }
    @Synchronized fun opened() { mutable.value = mutable.value.copy(socketOpen = true) }
    @Synchronized fun event(cursor: Long) { mutable.value = mutable.value.copy(requiredCursor = maxOf(cursor, mutable.value.requiredCursor)) }
    @Synchronized fun begin(): Long {
        mutable.value = mutable.value.copy(syncing = true)
        return mutable.value.generation
    }
    @Synchronized fun success(generation: Long, cursor: Long) {
        if (generation != mutable.value.generation) return
        lastSyncWall = System.currentTimeMillis(); record("同步完成")
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
        // OkHttp ping/pong failures invalidate the socket; idle sync age is not a transport failure.
        return ConnectionStatus.LIVE
    }
}
