package com.arcxya09.touch.data

import com.arcxya09.touch.security.LocalVault
import org.json.JSONObject

object Retention {
    const val DEFAULT_SECONDS = 7L * 86400
    fun cutoff(previous: Long, now: Long, seconds: Long) = maxOf(previous, (now - seconds).coerceAtLeast(0))
    fun retained(createdAt: Long, cutoff: Long) = createdAt > cutoff
}

/** Device-local, per account watermarks survive logout and increasing the retention period. */
class RetentionPolicy(private val vault: LocalVault, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val state by lazy {
        vault.read("retention")?.let { JSONObject(it.toString(Charsets.UTF_8)).also { json ->
            require(json.getInt("schema") == 1 && json.getLong("seconds") in 3600..31536000)
            json.getJSONObject("floors")
        } } ?: JSONObject().put("schema", 1).put("seconds", Retention.DEFAULT_SECONDS).put("floors", JSONObject())
    }
    val seconds: Long @Synchronized get() = state.getLong("seconds")
    @Synchronized fun cutoff(owner: String): Long {
        val floors = state.getJSONObject("floors")
        val previous = floors.optLong(owner, 0)
        val next = Retention.cutoff(previous, now(), seconds)
        if (next != previous || !floors.has(owner)) {
            val candidate = JSONObject(state.toString())
            candidate.getJSONObject("floors").put(owner, next)
            vault.write("retention", candidate.toString().toByteArray())
            floors.put(owner, next)
        }
        return next
    }
    @Synchronized fun configure(seconds: Long, owner: String?) {
        require(seconds in 3600..31536000) { "保留时间须为 1 小时至 365 天" }
        // Record the OLD cutoff before lengthening the window, even if this account is offline.
        state.getJSONObject("floors").keys().asSequence().toList().forEach { cutoff(it) }
        owner?.let { cutoff(it) }
        val candidate = JSONObject(state.toString()).put("seconds", seconds)
        vault.write("retention", candidate.toString().toByteArray())
        state.put("seconds", seconds)
        state.getJSONObject("floors").keys().asSequence().toList().forEach { cutoff(it) }
    }
}
