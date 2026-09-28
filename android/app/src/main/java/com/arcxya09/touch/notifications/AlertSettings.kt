package com.arcxya09.touch.notifications

import com.arcxya09.touch.security.LocalVault
import org.json.JSONObject

enum class AlertMode { DISCREET, CONTENT }

data class AlertOptions(val enabled: Boolean = false, val mode: AlertMode = AlertMode.DISCREET,
    val owner: String = "", val paused: Boolean = false) {
    fun canRun(userId: String?) = enabled && !paused && owner.isNotBlank() && owner == userId
}

/** Device/account-specific opt-in. No preference is stored as plaintext. */
class AlertSettings(private val vault: LocalVault) {
    private var cached: AlertOptions? = null
    @Synchronized fun read(): AlertOptions {
        cached?.let { return it }
        val value = vault.read("alerts")?.let { bytes ->
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("schema") == 1)
            AlertOptions(json.getBoolean("enabled"), AlertMode.valueOf(json.getString("mode")),
                json.getString("owner"), json.getBoolean("paused"))
        } ?: AlertOptions()
        cached = value
        return value
    }
    @Synchronized private fun save(value: AlertOptions): AlertOptions {
        vault.write("alerts", JSONObject().put("schema", 1).put("enabled", value.enabled)
            .put("mode", value.mode.name).put("owner", value.owner).put("paused", value.paused).toString().toByteArray())
        cached = value
        return value
    }
    @Synchronized fun enable(owner: String): AlertOptions {
        require(owner.isNotBlank())
        return save(AlertOptions(enabled = true, owner = owner))
    }
    @Synchronized fun disable() = save(AlertOptions())
    @Synchronized fun mode(mode: AlertMode): AlertOptions {
        val current = read()
        check(current.enabled)
        return save(current.copy(mode = mode))
    }
    @Synchronized fun pause(paused: Boolean) = save(read().copy(paused = paused))
}
