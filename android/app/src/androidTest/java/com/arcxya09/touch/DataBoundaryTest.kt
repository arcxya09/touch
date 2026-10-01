package com.arcxya09.touch

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.*
import com.arcxya09.touch.security.LocalVault
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Uses isolated encrypted databases and loopback HTTP, never a live account. */
class DataBoundaryTest {
    private val app get() = ApplicationProvider.getApplicationContext<TouchApp>()
    private fun session(owner: String) = JSONObject().put("access_token", "test-token").put("refresh_token", "test-refresh")
        .put("user", JSONObject().put("id", owner).put("username", owner).put("display_name", "测试账号"))
    private suspend fun isolated(block: suspend (Repository, TouchDatabase, File, SecureStore) -> Unit) = withContext(Dispatchers.IO) {
        val name = "quality-${UUID.randomUUID()}.db"
        val folder = File(app.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = folder }
        val db = EncryptedDatabase.open(context, app.vault, name)
        val secure = SecureStore(context)
        val prior = secure.read("session")
        val repo = Repository(context, { db }, secure)
        try { block(repo, db, folder, secure) }
        finally { repo.stop(); secure.write("session", prior); db.close(); app.deleteDatabase(name); folder.deleteRecursively() }
    }

    @Test fun unreadableCredentialsSealContentAndRequireOriginalAccount() = runBlocking {
        isolated { _, db, folder, secure ->
            val owner = "owner-${UUID.randomUUID()}"
            val cache = db.cache()
            val now = System.currentTimeMillis() / 1000
            cache.put(TouchDatabase.Item("meta", "owner", owner))
            cache.put(TouchDatabase.Item("draft", "c", JSONObject().put("created_at", now).put("text", "recoverable").toString()))
            cache.pending(TouchDatabase.Outbox("p", "c", "{}", now))
            val context = object : ContextWrapper(app) { override fun getCacheDir() = folder }
            secure.write("session", "{invalid-session")
            MockWebServer().use { server ->
                server.start()
                val repo = Repository(context, { db }, secure, Api(secure, server.url("/").toString().trimEnd('/')))
                try {
                    repo.initialize()
                    assertTrue(repo.credentialRecoveryRequired)
                    assertNull(repo.api.user)
                    assertEquals("{invalid-session", secure.read("session"))
                    assertNotNull(cache.get("draft", "c")); assertEquals(1, cache.pendingItems().size)
                    server.enqueue(MockResponse().setBody(session("another-owner").toString()))
                    assertTrue(runCatching { repo.login("another", "test") }.exceptionOrNull()?.message?.contains("原账号") == true)
                    assertNotNull(cache.get("draft", "c")); assertEquals(1, cache.pendingItems().size)
                    assertTrue(repo.credentialRecoveryRequired)
                    server.enqueue(MockResponse().setBody(session(owner).toString()))
                    repo.login(owner, "test")
                    assertFalse(repo.credentialRecoveryRequired)
                    assertEquals(owner, repo.api.user?.id)
                    assertNotNull(cache.get("draft", "c")); assertEquals(1, cache.pendingItems().size)
                } finally { repo.stop() }
            }
        }
    }

    @Test fun visibilityRulesSurviveAccountCacheReplacementAndRemainAccountScoped() = runBlocking {
        isolated { repo, db, _, _ ->
            val owner = UUID.randomUUID().toString()
            val cache = db.cache()
            val now = System.currentTimeMillis() / 1000
            val message = ChatMessage("deleted", "c", owner, "client", 1, "text", "private", now, null)
            cache.put(TouchDatabase.Item("meta", "owner", owner)); repo.purge()
            repo.deleteLocalMessage(message)
            repo.clearLocalHistory()
            val clearedThrough = cache.localCutoff(owner)!!.throughTime
            repo.clearLocal()
            assertTrue(cache.hidden(owner, message.id))
            assertTrue(clearedThrough >= now - 1)
            assertEquals("Clearing the cache must preserve the account's cutoff", clearedThrough, cache.localCutoff(owner)!!.throughTime)
            assertFalse(cache.hidden("another-owner", message.id))
            cache.put(TouchDatabase.Item("meta", "owner", owner)); repo.purge()
            assertFalse(repo.visible("c", ReplyRef(message.id, 1, now)))
            assertFalse(repo.visible("c", ReplyRef("older", 2, now - 10)))
            // Database/Keystore work can span seconds. "New" is after the committed
            // clear operation, not one second after the test started.
            assertFalse("The cutoff second remains hidden", repo.visible("c", ReplyRef("at-cutoff", 3, clearedThrough)))
            assertTrue("The next second is visible", repo.visible("c", ReplyRef("new", 4, clearedThrough + 1)))
            cache.put(TouchDatabase.Item("meta", "owner", "another-owner")); repo.purge()
            assertTrue(repo.visible("c", ReplyRef(message.id, 1, now)))
        }
    }

    @Test fun missingAccountMetadataFailsClosedWithoutPurgingRecoverableRows() = runBlocking {
        isolated { repo, db, _, _ ->
            val now = System.currentTimeMillis() / 1000
            db.cache().pending(TouchDatabase.Outbox("recoverable", "c", "{}", now))
            val failure = runCatching { repo.initialize() }.exceptionOrNull()
            assertTrue(failure?.message?.contains("已保留缓存") == true)
            assertEquals("recoverable", db.cache().pendingItems().single().id)
        }
    }

    @Test fun queueingConsumesOnlyTheSubmittedTextAndNeverAnAttachmentDraft() = runBlocking {
        isolated { repo, db, _, _ ->
            val now = System.currentTimeMillis() / 1000
            fun draft(text: String) = db.cache().put(TouchDatabase.Item("draft", "c",
                JSONObject().put("text", text).put("created_at", now).put("conversation_id", "c").toString()))
            val stopAfterQueue: suspend () -> Unit = { throw CancellationException("queued, offline fixture") }
            draft("尚未发送的文字")
            runCatching { repo.send("c", attachment = FileItem("file", "fixture.txt", "text/plain", "file", 3, "hash"), queued = stopAfterQueue) }
            assertEquals("尚未发送的文字", JSONObject(db.cache().get("draft", "c")!!.json).getString("text"))
            assertEquals("file", JSONObject(db.cache().pendingItems().single().body).getString("kind"))
            draft("发送期间编辑的新文字")
            runCatching { repo.send("c", text = "原提交文字", queued = stopAfterQueue) }
            assertEquals("发送期间编辑的新文字", JSONObject(db.cache().get("draft", "c")!!.json).getString("text"))
            draft("原提交文字")
            runCatching { repo.send("c", text = "原提交文字", queued = stopAfterQueue) }
            assertNull(db.cache().get("draft", "c"))
            assertEquals(3, db.cache().pendingItems().size)
        }
    }

    @Test fun interruptedFileDeletionIsPersistedAndRetriedAfterDatabaseReopens() = runBlocking {
        withContext(Dispatchers.IO) {
            val name = "cleanup-${UUID.randomUUID()}.db"
            val folder = File(app.cacheDir, name).apply { mkdirs() }
            val file = File(folder, "ciphertext").apply { writeText("encrypted-fixture") }
            val vault = LocalVault(app)
            var db = EncryptedDatabase.open(app, vault, name)
            try {
                val interrupted = AttachmentCleanup({ db.cache() }, { folder }) { false }
                db.runInTransaction { interrupted.enqueue(file.name) }
                assertFalse(interrupted.drain())
                assertTrue(db.cache().fileDeletionPending(file.name))
                db.close(); db = EncryptedDatabase.open(app, vault, name)
                assertTrue(AttachmentCleanup({ db.cache() }, { folder }).drain())
                assertFalse(file.exists()); assertFalse(db.cache().fileDeletionPending(file.name))
            } finally { db.close(); app.deleteDatabase(name); folder.deleteRecursively() }
        }
    }

    @Test fun cancelAfterHeadersInterruptsOnlyThatRequestIncludingBodyRead() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("x".repeat(10000)).throttleBody(1, 1, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setBody("other request completed"))
            val api = Api(SecureStore(app))
            val headers = CompletableDeferred<Unit>()
            val download = launch(Dispatchers.IO) {
                api.execute(Request.Builder().url(server.url("/slow")).build()).use {
                    headers.complete(Unit)
                    it.body!!.string()
                }
            }
            withTimeout(5000) { headers.await() }
            val other = async(Dispatchers.IO) { api.execute(Request.Builder().url(server.url("/other")).build()).use { it.body!!.string() } }
            withTimeout(3000) { download.cancelAndJoin() }
            assertEquals("other request completed", withTimeout(3000) { other.await() })
        }
    }
}
