package com.arcxya09.touch

import com.arcxya09.touch.data.ChatMessage
import com.arcxya09.touch.data.Repository
import org.json.JSONObject

/** The conversation loading boundary; commands continue to use the repository's transactions. */
internal interface ConversationReader {
    suspend fun draft(id: String): JSONObject?
    suspend fun messages(id: String): List<ChatMessage>
    suspend fun history(id: String, before: Long? = null): Boolean
}

internal class RepositoryConversationReader(private val repository: Repository) : ConversationReader {
    override suspend fun draft(id: String) = repository.draft(id)
    override suspend fun messages(id: String) = repository.messages(id)
    override suspend fun history(id: String, before: Long?) = repository.history(id, before)
}
