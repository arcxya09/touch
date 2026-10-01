package com.arcxya09.touch.data

import com.arcxya09.touch.security.LocalVault
import org.json.JSONObject

object Retention {
    const val DEFAULT_SECONDS = 3600L
    fun cutoff(previous: Long, now: Long, seconds: Long) = maxOf(previous, (now - seconds).coerceAtLeast(0))
    fun retained(createdAt: Long, cutoff: Long) = createdAt > cutoff
}

/** Device-local, per account watermarks survive logout and increasing the retention period. */
class RetentionPolicy(private val vault: LocalVault, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private var dirty = false
    private val state by lazy {
        vault.read("retention")?.let { JSONObject(it.toString(Charsets.UTF_8)).also { json ->
            require(json.getInt("schema") in 1..2 && json.getLong("seconds") in 3600..31536000)
            json.getJSONObject("floors")
            if (json.getInt("schema") == 1) {
                // Upgrade turns the formerly automatic policy off without reviving destroyed history.
                json.put("schema", 2).put("enabled", false).put("seconds", Retention.DEFAULT_SECONDS)
                vault.write("retention", json.toString().toByteArray())
            }
            json.getBoolean("enabled")
        } } ?: JSONObject().put("schema", 2).put("enabled", false).put("seconds", Retention.DEFAULT_SECONDS).put("floors", JSONObject())
    }
    val enabled: Boolean @Synchronized get() = state.getBoolean("enabled")
    val seconds: Long @Synchronized get() = state.getLong("seconds")
    @Synchronized fun visibilityCutoff(owner: String): Long {
        val floors = state.getJSONObject("floors")
        val previous = floors.optLong(owner, 0)
        val next = if (enabled) Retention.cutoff(previous, now(), seconds) else previous
        if (next != previous || !floors.has(owner)) {
            floors.put(owner, next)
            dirty = true
        }
        return next
    }
    /** Persist at maintenance/lifecycle boundaries, rather than once per render tick. */
    @Synchronized fun cutoff(owner: String): Long {
        val next = visibilityCutoff(owner)
        if (dirty) {
            vault.write("retention", state.toString().toByteArray())
            dirty = false
        }
        return next
    }
    @Synchronized fun setEnabled(enabled: Boolean, owner: String?) {
        state.getJSONObject("floors").keys().asSequence().toList().forEach { cutoff(it) }
        owner?.let { cutoff(it) }
        val candidate = JSONObject(state.toString()).put("enabled", enabled)
        if (enabled && !this.enabled) candidate.put("seconds", Retention.DEFAULT_SECONDS)
        vault.write("retention", candidate.toString().toByteArray())
        state.put("enabled", enabled).put("seconds", candidate.getLong("seconds"))
        state.getJSONObject("floors").keys().asSequence().toList().forEach { cutoff(it) }
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
