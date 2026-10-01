package com.arcxya09.touch

/** Navigation state stays in memory: Android saved state must not contain chat content. */
enum class Screen(val route: String) {
    Timer("timer"), Home("home"), Chat("chat"), Contacts("contacts"), Settings("settings"),
    Notifications("notifications"), Profile("profile"), Password("password"), Preview("preview"),
    PrivacySecurity("privacy-security"), LocalData("local-data"), About("about");

    fun parent(): Screen = when (this) {
        Preview -> Chat
        Notifications, Profile, Password, PrivacySecurity, LocalData, About -> Settings
        else -> Home
    }

    companion object {
        fun fromRoute(value: String): Screen = entries.firstOrNull { it.route == value } ?: Home
    }
}

enum class Operation { Session, Conversation, Send, Attachment, Profile, Settings, Contacts, Update, Export }
enum class OperationStatus { Idle, Running, Succeeded, Failed }
data class OperationUiState(val status: OperationStatus = OperationStatus.Idle, val error: String? = null) {
    val working get() = status == OperationStatus.Running
}
enum class SessionGate { Loading, Locked, PrivacySetup, SignedOut, Ready, CredentialsRecovery, StorageUnavailable }

/** Late results can only update the exact account and conversation visit which requested them. */
internal class ConversationRequests {
    data class Ticket(val owner: String?, val conversationId: String?, val generation: Long)
    private var generation = 0L
    fun invalidate() { generation++ }
    fun ticket(owner: String?, conversationId: String?) = Ticket(owner, conversationId, generation)
    fun accepts(ticket: Ticket, owner: String?, conversationId: String?) =
        ticket.generation == generation && ticket.owner == owner && ticket.conversationId == conversationId
}

/** Coalesces byte-level callbacks without delaying the first or completed state. */
internal class ProgressUpdates(private val now: () -> Long) {
    private var lastTime: Long? = null
    @Synchronized fun accept(progress: Float): Boolean {
        val time = now()
        if (lastTime == null || progress >= 1f || time - lastTime!! >= 100L) {
            lastTime = time
            return true
        }
        return false
    }
}
