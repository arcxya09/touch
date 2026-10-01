package com.arcxya09.touch

import com.arcxya09.touch.security.LocalVault
import org.json.JSONObject

/** Only identifiers are retained, inside the existing encrypted vault, never in a Bundle. */
internal data class PendingExport(val owner: String, val conversationId: String, val messageId: String, val expiresAt: Long,
                                  val destinationUri: String? = null) {
    fun validFor(owner: String?, now: Long) = this.owner == owner && now < expiresAt
}

internal class PendingExportStore(private val vault: LocalVault) {
    fun read(): PendingExport? {
        val value = vault.read("pending-export")?.let { JSONObject(it.toString(Charsets.UTF_8)) } ?: return null
        if (!value.has("owner")) return null
        return PendingExport(value.getString("owner"), value.getString("conversation"), value.getString("message"), value.getLong("expires"),
            value.optString("destination").takeIf { it.isNotBlank() })
    }
    fun write(value: PendingExport) = vault.write("pending-export", JSONObject()
        .put("owner", value.owner).put("conversation", value.conversationId).put("message", value.messageId)
        .put("expires", value.expiresAt).put("destination", value.destinationUri).toString().toByteArray(Charsets.UTF_8))
    fun clear() = vault.write("pending-export", "{}".toByteArray(Charsets.UTF_8))
}
