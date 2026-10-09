package com.arcxya09.touch

import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.*
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Keeps unsubmitted text when an attachment (with a reply) is queued, including offline retry. */
class AttachmentDraftInstrumentedTest {
    @Test fun queuedAttachmentPreservesTextAndConsumesOnlyItsReply() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val name = "attachment-draft-${UUID.randomUUID()}.db"
        val owner = UUID.randomUUID().toString()
        val folder = File(app.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = folder }
        val secure = SecureStore(context)
        val priorSession = secure.read("session"); val priorPrivacy = secure.read("privacy")
        val marker = File(app.filesDir, "privacy.enabled")
        val priorMarker = marker.takeIf(File::exists)?.readBytes()
        val db = withContext(Dispatchers.IO) { EncryptedDatabase.open(context, app.vault, name) }
        val store = ViewModelStore()
        MockWebServer().use { server ->
            val source = File(folder, "fixture.txt").apply { writeText("attachment fixture") }
            val attachment = JSONObject().put("id", "file-one").put("name", source.name).put("mime", "text/plain")
                .put("kind", "file").put("size", source.length()).put("sha256", sha256(source))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/api/v1/files?") == true -> MockResponse().setBody(attachment.toString())
                    else -> MockResponse().setResponseCode(503).setBody("{\"detail\":\"offline fixture\"}")
                }
            }
            server.start()
            val repo = Repository(context, { db }, secure, Api(secure, server.url("/").toString().trimEnd('/')))
            val now = System.currentTimeMillis() / 1000
            val original = JSONObject().put("id", "original").put("conversation_id", "A").put("sender_id", owner)
                .put("client_id", "original-client").put("seq", 1).put("kind", "text").put("text", "original").put("created_at", now)
            try {
                withContext(Dispatchers.IO) {
                    db.cache().put(TouchDatabase.Item("meta", "owner", owner))
                    db.cache().put(TouchDatabase.Item("message", "original", original.toString()))
                    secure.write("session", JSONObject().put("access_token", "local-token").put("refresh_token", "local-refresh")
                        .put("user", JSONObject().put("id", owner).put("username", owner).put("display_name", "本机测试")
                            .put("must_change_password", true)).toString())
                    secure.write("privacy", JSONObject().put("enabled", false).toString())
                    check(!marker.exists() || marker.delete())
                    repo.initialize(); repo.setDraftEnabled(true)
                }
                val reader = object : ConversationReader {
                    override suspend fun draft(id: String) = repo.draft(id)
                    override suspend fun messages(id: String) = repo.messages(id)
                    override suspend fun history(id: String, before: Long?): Boolean = awaitCancellation()
                }
                val vm = withContext(Dispatchers.Main) { AppViewModel(app, repo, reader).also { store.put("attachment", it) } }
                awaitState { vm.initialized && !vm.storageError }
                withContext(Dispatchers.Main) { vm.resume() }
                awaitState { vm.mayShowChat }
                withContext(Dispatchers.Main) { vm.openConversation("A") }
                awaitState { vm.messages.any { it.id == "original" } }
                withContext(Dispatchers.Main) {
                    vm.editDraft("This text has not been sent")
                    vm.quoteMessage(ChatMessage.parse(original))
                    vm.pendingSelection = Uri.fromFile(source) to "file"
                }
                withTimeout(5000) { while (repo.draft("A")?.optJSONObject("reply_to") == null) delay(20) }
                withContext(Dispatchers.Main) { vm.sendSelection() }
                awaitState { vm.operationState(Operation.Attachment).status == OperationStatus.Succeeded }
                withContext(Dispatchers.Main) {
                    assertEquals("This text has not been sent", vm.draftText)
                    assertNull(vm.quote)
                    assertNull("A lost server response leaves a pending message, not a failed attachment upload", vm.error)
                }
                assertEquals(PendingDelivery.Unconfirmed, repo.messages("A").single { it.pending }.pendingDelivery)
                withTimeout(5000) {
                    while (true) {
                        val saved = repo.draft("A")
                        if (saved?.optString("text") == "This text has not been sent" && saved.optJSONObject("reply_to") == null) break
                        delay(20)
                    }
                }
                withContext(Dispatchers.IO) {
                    val queued = JSONObject(db.cache().pendingItems().single().body)
                    assertEquals("", queued.getString("text"))
                    assertEquals("file-one", queued.getString("attachment_id"))
                    assertEquals("original", queued.getJSONObject("reply_to").getString("id"))
                }
            } finally {
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
}
