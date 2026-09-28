package com.arcxya09.touch.security

import org.json.JSONObject

data class SafetyOptions(val hideRecents: Boolean = false, val flipExit: Boolean = false, val shakeExit: Boolean = false) {
    fun save(vault: LocalVault) = vault.write("safety", JSONObject().put("schema", 1)
        .put("hideRecents", hideRecents).put("flipExit", flipExit).put("shakeExit", shakeExit).toString().toByteArray())
    companion object {
        fun read(vault: LocalVault): SafetyOptions {
            val value = vault.read("safety") ?: return SafetyOptions()
            val json = JSONObject(value.toString(Charsets.UTF_8))
            require(json.getInt("schema") == 1)
            return SafetyOptions(json.getBoolean("hideRecents"), json.getBoolean("flipExit"), json.getBoolean("shakeExit"))
        }
    }
}
