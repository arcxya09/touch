package com.arcxya09.touch

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.*
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ContactMutationTest {
    @Test fun confirmedRelationsRemainCorrectWhenTheNextSyncFails() = verify(acceptMutation = true)
    @Test fun rejectedMutationDoesNotChangeTheLocalRelation() = verify(acceptMutation = false)

    private fun verify(acceptMutation: Boolean) = runBlocking { withContext(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val name = "contact-mutation-${UUID.randomUUID()}.db"
        val folder = File(app.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = folder }
        val secure = SecureStore(context)
        val priorSession = secure.read("session")
        val db = EncryptedDatabase.open(context, app.vault, name)
        MockWebServer().use { server ->
            val user = JSONObject().put("id", UUID.randomUUID().toString()).put("username", "local-owner").put("display_name", "测试账号")
            val peer = JSONObject().put("id", UUID.randomUUID().toString()).put("username", "local-peer").put("display_name", "测试联系人")
            val contact = JSONObject().put("id", "relation").put("peer", peer).put("state", "pending")
                .put("incoming", true).put("conversation_id", JSONObject.NULL)
            val conversation = JSONObject().put("id", "conversation").put("peer", peer).put("clear_seq", 0)
                .put("unread", 0).put("can_send", true).put("last_message", JSONObject.NULL)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "POST" && !acceptMutation -> MockResponse().setResponseCode(409).setBody("{\"detail\":\"fixture conflict\"}")
                    request.path == "/api/v1/contacts/requests" -> MockResponse().setBody("{\"ok\":true,\"state\":\"pending\"}")
                    request.method == "POST" && request.path == "/api/v1/contacts/${peer.getString("id")}" -> MockResponse().setBody("{\"ok\":true}")
                    else -> MockResponse().setResponseCode(503).setBody("{\"detail\":\"fixture sync unavailable\"}")
                }
            }
            server.start()
            val repo = Repository(context, { db }, secure, Api(secure, server.url("/").toString().trimEnd('/')))
            try {
                db.cache().put(TouchDatabase.Item("meta", "owner", user.getString("id")))
                db.cache().put(TouchDatabase.Item("contact", "relation", contact.toString()))
                db.cache().put(TouchDatabase.Item("conversation", "conversation", conversation.toString()))
                secure.write("session", JSONObject().put("access_token", "local-contact-token")
                    .put("refresh_token", "local-contact-refresh").put("user", user).toString())
                repo.initialize()
                val person = Person.parse(peer)
                if (acceptMutation) {
                    assertEquals("pending", repo.request(person))
                    assertEquals("A confirmed request must not require another network response", 1, server.requestCount)
                    repo.contactAction(person.id, "accept")
                    assertEquals("accepted", repo.contacts().single().state)
                    assertNull("The conversation id must come from synchronization, not be invented", repo.contacts().single().conversationId)
                    assertEquals(2, server.requestCount)
                    val failure = runCatching { repo.sync() }.exceptionOrNull()
                    assertTrue(failure is ApiException && failure.status == 503)
                    assertEquals("accepted", repo.contacts().single().state)
                    repo.contactAction(person.id, "remove")
                    assertTrue(repo.contacts().isEmpty())
                    assertFalse(repo.conversations().single().canSend)
                    db.cache().put(TouchDatabase.Item("contact", "relation", contact.toString()))
                    repo.contactAction(person.id, "reject")
                    assertTrue(repo.contacts().isEmpty())
                } else {
                    val failure = runCatching { repo.contactAction(person.id, "accept") }.exceptionOrNull()
                    assertTrue(failure is ApiException && failure.status == 409)
                    assertEquals("pending", repo.contacts().single().state)
                    assertTrue(repo.conversations().single().canSend)
                    assertEquals(1, server.requestCount)
                }
            } finally {
                repo.stop(); secure.write("session", priorSession)
                db.close(); app.deleteDatabase(name); folder.deleteRecursively()
            }
        }
    } }
}
