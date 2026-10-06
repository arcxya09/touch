package com.arcxya09.touch

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.*
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class LocalMessageActionsTest {
    @Test fun recallIgnoresCachedPeerCapabilityAndRemovesOriginalContentAndAttachment() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val app = ApplicationProvider.getApplicationContext<TouchApp>()
            val name = "recall-actions-${UUID.randomUUID()}.db"
            val folder = File(app.cacheDir, name).apply { mkdirs() }
            val context = object : ContextWrapper(app) { override fun getCacheDir() = folder }
            val secure = SecureStore(context)
            val priorSession = secure.read("session")
            val db = EncryptedDatabase.open(context, app.vault, name)
            MockWebServer().use { server ->
                val owner = UUID.randomUUID().toString()
                val peerId = UUID.randomUUID().toString()
                val cid = UUID.randomUUID().toString()
                val mid = UUID.randomUUID().toString()
                val fileId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis() / 1000
                // Legacy peer metadata has no capability declaration; no peer session is involved.
                val user = JSONObject().put("id", owner).put("username", "recall-owner").put("display_name", "本机测试")
                val peer = JSONObject().put("id", peerId).put("username", "legacy-peer").put("display_name", "旧版联系人")
                val attachment = JSONObject().put("id", fileId).put("name", "private.txt").put("mime", "text/plain")
                    .put("kind", "file").put("size", 15).put("sha256", "0".repeat(64)).put("created_at", now)
                val original = JSONObject().put("id", mid).put("conversation_id", cid).put("sender_id", owner)
                    .put("client_id", mid).put("seq", 1).put("kind", "file").put("text", "private original content")
                    .put("created_at", now).put("attachment", attachment)
                val recalled = JSONObject(original.toString()).put("kind", "recalled").put("text", "")
                    .put("attachment", JSONObject.NULL)
                val conversation = JSONObject().put("id", cid).put("peer", peer).put("unread", 0).put("clear_seq", 0)
                    .put("can_send", true).put("can_recall", false).put("last_message", original)
                val recallPath = "/api/v1/conversations/$cid/messages/$mid/recall"
                val syncedConversation = JSONObject(conversation.toString()).put("last_message", JSONObject.NULL)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.method == "POST" && request.path == recallPath -> MockResponse().setBody("{\"ok\":true}")
                        request.path == "/api/v1/auth/me" -> MockResponse().setBody(user.toString())
                        request.path?.startsWith("/api/v1/sync?") == true -> MockResponse().setBody(JSONObject()
                            .put("events", JSONArray().put(JSONObject().put("kind", "message").put("payload", recalled)))
                            .put("cursor", 2).put("has_more", false).toString())
                        request.path?.startsWith("/api/v1/conversations?") == true -> MockResponse().setBody(JSONArray().put(syncedConversation).toString())
                        request.path == "/api/v1/contacts" -> MockResponse().setBody("[]")
                        else -> MockResponse().setResponseCode(404).setBody("{\"detail\":\"unexpected fixture request\"}")
                    }
                }
                server.start()
                val repo = Repository(context, { db }, secure, Api(secure, server.url("/").toString().trimEnd('/')))
                try {
                    val cache = db.cache()
                    cache.put(TouchDatabase.Item("meta", "owner", owner))
                    cache.put(TouchDatabase.Item("conversation", cid, conversation.toString()))
                    cache.put(TouchDatabase.Item("message", mid, original.toString()))
                    cache.put(TouchDatabase.Item("attachment", fileId, attachment.toString()))
                    secure.write("session", JSONObject().put("access_token", "local-recall-token")
                        .put("refresh_token", "local-recall-refresh").put("user", user).toString())
                    repo.initialize()
                    val encrypted = EncryptedAttachments(context, app.vault).file(FileItem.parse(attachment), owner, now) { true }
                    encrypted.encryptTo(encrypted.encryptedFile).use { it.write("private fixture".toByteArray()) }
                    val cached = repo.conversations().single()
                    assertFalse("A stale capability value must not prevent the sender's recall", cached.canRecall)
                    assertTrue("The peer does not advertise any modern capability", cached.peer.capabilities.isEmpty())
                    assertEquals(0, server.requestCount)
                    assertTrue(encrypted.encryptedFile.exists())

                    repo.recallMessage(ChatMessage.parse(original))

                    val first = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Recall did not reach the server")
                    assertEquals("Recall must be sent before any auth/profile sync", recallPath, first.path)
                    assertEquals("POST", first.method)
                    assertEquals("Bearer local-recall-token", first.getHeader("Authorization"))
                    assertEquals(5, server.requestCount)
                    val tombstone = JSONObject(cache.get("message", mid)!!.json)
                    assertEquals("recalled", tombstone.getString("kind"))
                    assertEquals("", tombstone.getString("text"))
                    assertTrue(tombstone.isNull("attachment"))
                    assertFalse(tombstone.toString().contains("private original content"))
                    assertNotNull(cache.get("recalled-message", mid))
                    assertNull(cache.get("attachment", fileId))
                    assertFalse("The original encrypted attachment must be deleted", encrypted.encryptedFile.exists())
                    assertTrue(cache.fileDeletions().isEmpty())
                    assertTrue(repo.messages(cid).isEmpty())
                    assertNull(repo.original(cid, ReplyRef(mid, 1, now)))
                    assertFalse("Recall succeeds while cached peer metadata remains legacy", repo.conversations().single().canRecall)
                } finally {
                    repo.stop(); secure.write("session", priorSession)
                    db.close(); app.deleteDatabase(name); folder.deleteRecursively()
                }
            }
        }
    }

    @Test fun deletionAndClearSurviveReloadWithoutDiscardingAccountSettings() = runBlocking {
        withContext(Dispatchers.IO) {
            val app = ApplicationProvider.getApplicationContext<TouchApp>()
            val name = "local-actions-${UUID.randomUUID()}.db"
            val folder = File(app.cacheDir, name).apply { mkdirs() }
            val context = object : ContextWrapper(app) { override fun getCacheDir() = folder }
            val db = EncryptedDatabase.open(context, app.vault, name)
            val repo = Repository(context, { db }, SecureStore(context))
            val cache = db.cache()
            val owner = UUID.randomUUID().toString()
            val now = System.currentTimeMillis() / 1000
            fun message(id: String, seq: Long, time: Long) = JSONObject()
                .put("id", id).put("conversation_id", "c").put("sender_id", owner).put("client_id", id)
                .put("seq", seq).put("kind", "text").put("text", "private").put("created_at", time)
            try {
                cache.put(TouchDatabase.Item("meta", "owner", owner))
                cache.put(TouchDatabase.Item("meta", "drafts", "true"))
                cache.put(TouchDatabase.Item("meta", "cursor", "42"))
                cache.put(TouchDatabase.Item("contact", "peer", "{}"))
                repo.purge()
                val json = message("m", 1, now)
                cache.put(TouchDatabase.Item("message", "m", json.toString()))
                repo.deleteLocalMessage(ChatMessage.parse(json))
                repo.purge()
                assertFalse(repo.visible("c", ReplyRef("m", 1, now)))
                assertNull(repo.original("c", ReplyRef("m", 1, now)))
                assertTrue(repo.messages("c").isEmpty())
                cache.put(TouchDatabase.Item("message", "old", message("old", 2, now - 10).toString()))
                cache.pending(TouchDatabase.Outbox("pending", "c", "{}", now))
                cache.put(TouchDatabase.Item("draft", "c", JSONObject().put("created_at", now).toString()))
                val attachment = File(folder, "sealed-attachments/test").apply { parentFile!!.mkdirs(); writeText("fixture") }
                repo.clearLocalHistory()
                repo.purge()
                assertFalse(repo.visible("c", ReplyRef("old", 2, now - 10)))
                assertTrue(repo.messages("c").isEmpty())
                assertTrue(cache.pendingItems().isEmpty())
                assertTrue(cache.items("draft").isEmpty())
                assertFalse(attachment.exists())
                assertEquals(owner, cache.get("meta", "owner")!!.json)
                assertEquals("true", cache.get("meta", "drafts")!!.json)
                assertEquals("42", cache.get("meta", "cursor")!!.json)
                assertNotNull(cache.get("contact", "peer"))
                assertTrue(repo.visible("c", ReplyRef("new", 3, System.currentTimeMillis() / 1000)))
                // Markers are persisted, not just held by the screen or repository instance.
                assertNotNull(cache.get("hidden-message", "m"))
                assertNotNull(cache.get("meta", "local-clear-time"))
            } finally {
                repo.stop(); db.close(); app.deleteDatabase(name); folder.deleteRecursively()
            }
        }
    }
}
