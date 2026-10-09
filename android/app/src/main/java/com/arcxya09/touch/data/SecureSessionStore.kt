package com.arcxya09.touch.data

import com.arcxya09.touch.security.SecureStore
import org.json.JSONObject

/** Credential failures never imply permission to delete independently encrypted local content. */
class SecureSessionStore(private val secure: SecureStore,
                         private val persist: suspend (String?) -> Unit = { secure.write("session", it) }) {
    @Volatile var value: JSONObject? = null
        private set

    suspend fun load() {
        value = null
        val restored = secure.read("session")?.let(::JSONObject)
        restored?.let {
            Person.parse(it.getJSONObject("user"))
            require(it.getString("access_token").isNotBlank() && it.getString("refresh_token").isNotBlank()) { "登录凭据不完整" }
        }
        value = restored
    }

    suspend fun save(session: JSONObject?) {
        persist(session?.toString())
        value = session
    }

    fun seal() { value = null }
}
