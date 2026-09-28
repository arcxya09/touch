package com.arcxya09.touch.data

import org.json.JSONObject

data class Person(val id: String, val username: String, val name: String, val mustChange: Boolean = false) {
    companion object { fun parse(j: JSONObject) = Person(j.getString("id"), j.getString("username"), j.getString("display_name"), j.optBoolean("must_change_password")) }
}
data class FileItem(val id: String, val name: String, val mime: String, val kind: String, val size: Long, val sha256: String) {
    companion object { fun parse(j: JSONObject) = FileItem(j.getString("id"), j.getString("name"), j.getString("mime"), j.getString("kind"), j.getLong("size"), j.getString("sha256")) }
}
data class ChatMessage(val id: String, val conversationId: String, val senderId: String, val clientId: String,
    val seq: Long, val kind: String, val text: String, val createdAt: Long, val file: FileItem?, val pending: Boolean = false) {
    companion object { fun parse(j: JSONObject) = ChatMessage(j.getString("id"), j.getString("conversation_id"), j.getString("sender_id"), j.getString("client_id"), j.getLong("seq"), j.getString("kind"), j.optString("text"), j.getLong("created_at"), j.optJSONObject("attachment")?.let(FileItem::parse)) }
}
data class Conversation(val id: String, val peer: Person, val unread: Int, val clearSeq: Long, val canSend: Boolean, val last: ChatMessage?) {
    companion object { fun parse(j: JSONObject) = Conversation(j.getString("id"), Person.parse(j.getJSONObject("peer")), j.getInt("unread"), j.getLong("clear_seq"), j.getBoolean("can_send"), j.optJSONObject("last_message")?.let(ChatMessage::parse)) }
}
data class ContactItem(val id: String, val peer: Person, val state: String, val incoming: Boolean, val conversationId: String?) {
    companion object { fun parse(j: JSONObject) = ContactItem(j.getString("id"), Person.parse(j.getJSONObject("peer")), j.getString("state"), j.getBoolean("incoming"), j.optString("conversation_id").takeUnless { it == "null" || it.isBlank() }) }
}

class ApiException(val status: Int, message: String, val reason: String = "") : Exception(message)
