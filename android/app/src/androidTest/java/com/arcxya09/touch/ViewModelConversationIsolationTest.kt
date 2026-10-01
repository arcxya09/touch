package com.arcxya09.touch

import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.*
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises the actual ViewModel with real encrypted storage and deliberately late page reads. */
class ViewModelConversationIsolationTest {
    @Test fun delayedMessagesCannotOverwriteNewVisitToSameConversation() = verifyLateResult(delayedDraft = false)
    @Test fun delayedDraftCannotOverwriteNewVisitToSameConversation() = verifyLateResult(delayedDraft = true)

    private fun verifyLateResult(delayedDraft: Boolean) = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val name = "conversation-race-${UUID.randomUUID()}.db"
        val owner = "local-${UUID.randomUUID()}"
        val folder = File(app.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = folder }
        val secure = SecureStore(context)
        val priorSession = secure.read("session")
        val priorPrivacy = secure.read("privacy")
        val marker = File(app.filesDir, "privacy.enabled")
        val priorMarker = marker.takeIf(File::exists)?.readBytes()
        val db = withContext(Dispatchers.IO) { EncryptedDatabase.open(context, app.vault, name) }
        val reader = DeferredReader(owner, delayedDraft)
        val store = ViewModelStore()
        MockWebServer().use { server ->
            server.start()
            val repo = Repository(context, { db }, secure, Api(secure, server.url("/").toString().trimEnd('/')))
            try {
                withContext(Dispatchers.IO) {
                    db.cache().put(TouchDatabase.Item("meta", "owner", owner))
                    secure.write("session", JSONObject().put("access_token", "local-token").put("refresh_token", "local-refresh")
                        .put("user", JSONObject().put("id", owner).put("username", owner).put("display_name", "本机测试")
                            .put("must_change_password", true)).toString())
                    // The synthetic must-change session suppresses foreground sync. Any accidental HTTP is loopback only.
                    secure.write("privacy", JSONObject().put("enabled", false).toString())
                    check(!marker.exists() || marker.delete())
                    repo.initialize(); repo.setDraftEnabled(true)
                }
                val vm = withContext(Dispatchers.Main) { AppViewModel(app, repo, reader).also { store.put("race", it) } }
                awaitState { vm.initialized && !vm.storageError }
                withContext(Dispatchers.Main) { vm.resume() }
                awaitState { vm.mayShowChat }
                withContext(Dispatchers.Main) { vm.openConversation("A") }
                withTimeout(5000) { reader.firstEntered.await() }
                withContext(Dispatchers.Main) { vm.openConversation("B") }
                awaitState { vm.conversationId == "B" && vm.messages.singleOrNull()?.text == "current B" }
                withContext(Dispatchers.Main) { vm.openConversation("A") }
                awaitState { vm.messages.singleOrNull()?.text == "current A" && vm.draftText == "current draft A" }
                reader.releaseFirst.complete(Unit)
                withTimeout(5000) { reader.firstReturned.await() }
                withContext(Dispatchers.Main) { yield() }
                withContext(Dispatchers.Main) {
                    assertEquals("A", vm.conversationId)
                    assertEquals("current A", vm.messages.single().text)
                    assertEquals("current draft A", vm.draftText)
                    assertTrue("The new A load must retain its own operation state", vm.isWorking(Operation.Conversation))
                }
                assertEquals("The isolation test must make no HTTP requests", 0, server.requestCount)
            } finally {
                reader.releaseFirst.complete(Unit)
                withContext(Dispatchers.Main) { store.clear() }
                withContext(Dispatchers.IO) {
                    repo.stop(); secure.write("session", priorSession); secure.write("privacy", priorPrivacy)
                    if (priorMarker != null) marker.writeBytes(priorMarker) else marker.delete()
                    db.close(); app.deleteDatabase(name); folder.deleteRecursively()
                }
            }
        }
    }

    private suspend fun awaitState(check: () -> Boolean) = withTimeout(5000) {
        while (!withContext(Dispatchers.Main) { check() }) delay(10)
    }

    private class DeferredReader(private val owner: String, private val delayedDraft: Boolean) : ConversationReader {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstReturned = CompletableDeferred<Unit>()
        private var draftReadsA = 0
        private var messageReadsA = 0
        private suspend fun completeLate() {
            firstEntered.complete(Unit)
            // Models a disk/provider result that finishes after its cancelled request, without respecting cancellation.
            withContext(NonCancellable) { releaseFirst.await() }
            firstReturned.complete(Unit)
        }
        override suspend fun draft(id: String): JSONObject {
            val firstA = id == "A" && ++draftReadsA == 1
            if (firstA && delayedDraft) completeLate()
            return JSONObject().put("text", if (firstA && delayedDraft) "obsolete draft A" else "current draft $id")
                .put("created_at", System.currentTimeMillis() / 1000)
        }
        override suspend fun messages(id: String): List<ChatMessage> {
            val firstA = id == "A" && ++messageReadsA == 1
            if (firstA && !delayedDraft) completeLate()
            val text = if (firstA && !delayedDraft) "obsolete A" else "current $id"
            return listOf(ChatMessage("$id-$text", id, owner, "client-$text", 1, "text", text,
                System.currentTimeMillis() / 1000, null))
        }
        override suspend fun history(id: String, before: Long?): Boolean = awaitCancellation()
    }
}
