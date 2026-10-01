package com.arcxya09.touch

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.*
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalMessageActionsTest {
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
