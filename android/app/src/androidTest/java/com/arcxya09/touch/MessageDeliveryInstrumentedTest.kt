package com.arcxya09.touch

import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.*
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Local-only transport fixtures exercise delivery acknowledgement rather than optimistic UI timing. */
class MessageDeliveryInstrumentedTest {
    @Test fun queuedMessageIsAlreadySendingBeforeItsFirstUiSnapshot() = runBlocking {
        fixture { f ->
            f.server.dispatcher = f.dispatch { request -> MockResponse().setBody(f.ack(request).toString()) }
            var queuedId: String? = null
            val id = f.repo.send("A", "queued") {
                val queued = f.repo.messages("A").single()
                queuedId = queued.id
                assertTrue(queued.pending)
                assertEquals(PendingDelivery.Queued, queued.pendingDelivery)
                assertTrue("Publishing a queued row must not precede the sending marker", queued.id in f.repo.sending.value)
            }
            assertEquals(id, queuedId)
            assertTrue(f.repo.sending.value.isEmpty())
            val delivered = f.repo.messages("A").single()
            assertFalse(delivered.pending)
            assertEquals(id, delivered.clientId)
        }
    }

    @Test fun lostResponseReconcilesFromSyncWithoutReportingFailure() = runBlocking {
        fixture { f ->
            val accepted = AtomicReference<JSONObject>()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "POST" -> {
                        accepted.set(f.ack(request))
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    request.path == "/api/v1/auth/me" -> MockResponse().setBody(f.user.toString())
                    request.path?.startsWith("/api/v1/sync?") == true -> MockResponse().setBody(JSONObject()
                        .put("events", JSONArray().put(JSONObject().put("kind", "message").put("payload", accepted.get())))
                        .put("cursor", 1).put("has_more", false).toString())
                    else -> MockResponse().setBody("[]")
                }
            }
            val id = withTimeout(10_000) { f.repo.send("A", "response lost") }
            assertEquals(PendingDelivery.Unconfirmed, f.repo.messages("A").single().pendingDelivery)
            assertTrue(f.repo.sending.value.isEmpty())
            f.repo.sync()
            val delivered = f.repo.messages("A").single()
            assertFalse(delivered.pending)
            assertEquals(id, delivered.clientId)
            assertTrue(withContext(Dispatchers.IO) { f.db.cache().pendingItems().isEmpty() })
        }
    }

    @Test fun ambiguousResponseSurvivesReloadAndRetryReusesTheSameClientId() = runBlocking {
        fixture { f ->
            val attempts = AtomicInteger()
            val accepted = AtomicReference<JSONObject>()
            f.server.dispatcher = f.dispatch { request ->
                val response = f.ack(request)
                assertFalse(JSONObject(request.body.clone().readUtf8()).has("_delivery_state"))
                if (attempts.incrementAndGet() == 1) {
                    accepted.set(response)
                    MockResponse().setResponseCode(503)
                } else {
                    assertEquals(accepted.get().getString("client_id"), response.getString("client_id"))
                    MockResponse().setBody(accepted.get().toString())
                }
            }
            val id = f.repo.send("A", "server accepted before gateway failed")
            val restored = Repository(f.context, { f.db }, f.secure, Api(f.secure, f.server.url("/").toString().trimEnd('/')))
            try {
                restored.initialize()
                assertEquals(PendingDelivery.Unconfirmed, restored.messages("A").single().pendingDelivery)
                restored.retry(id)
                val messages = restored.messages("A")
                assertEquals(1, messages.size)
                assertFalse(messages.single().pending)
                assertEquals(id, messages.single().clientId)
                assertEquals(2, attempts.get())
            } finally { restored.stop() }
        }
    }

    @Test fun explicitRejectionIsFailedAndRetryKeepsSendingUntilAcknowledged() = runBlocking {
        fixture { f ->
            val attempts = AtomicInteger()
            val receivedRetry = CountDownLatch(1)
            val releaseRetry = CountDownLatch(1)
            f.server.dispatcher = f.dispatch { request ->
                if (attempts.incrementAndGet() == 1) MockResponse().setResponseCode(403).setBody("{\"detail\":\"blocked fixture\"}")
                else {
                    receivedRetry.countDown()
                    check(releaseRetry.await(5, TimeUnit.SECONDS))
                    MockResponse().setBody(f.ack(request).toString())
                }
            }
            val failure = runCatching { f.repo.send("A", "rejected") }.exceptionOrNull()
            assertTrue(failure is ApiException && failure.status == 403)
            val pending = f.repo.messages("A").single()
            assertEquals(PendingDelivery.Failed, pending.pendingDelivery)
            val retry = async { f.repo.retry(pending.id) }
            try {
                assertTrue(withContext(Dispatchers.IO) { receivedRetry.await(5, TimeUnit.SECONDS) })
                assertTrue(pending.id in f.repo.sending.value)
                assertEquals(PendingDelivery.Unconfirmed, f.repo.messages("A").single().pendingDelivery)
            } finally { releaseRetry.countDown() }
            retry.await()
            assertFalse(f.repo.messages("A").single().pending)
        }
    }

    @Test fun acknowledgedSendDoesNotWaitForOrInheritFollowupSyncErrors() = runBlocking {
        fixture(mustChangePassword = true) { f ->
            val store = ViewModelStore()
            val posts = AtomicInteger()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.method == "POST") {
                    posts.incrementAndGet()
                    MockResponse().setBody(f.ack(request).toString())
                } else MockResponse().setResponseCode(503)
            }
            val historyStarted = CompletableDeferred<Unit>()
            val reader = object : ConversationReader {
                override suspend fun draft(id: String) = f.repo.draft(id)
                override suspend fun messages(id: String) = f.repo.messages(id)
                override suspend fun history(id: String, before: Long?): Boolean { historyStarted.complete(Unit); return awaitCancellation() }
            }
            try {
                val vm = withContext(Dispatchers.Main) { AppViewModel(f.app, f.repo, reader).also { store.put("delivery", it) } }
                awaitState { vm.initialized && !vm.storageError }
                withContext(Dispatchers.Main) { vm.resume() }
                awaitState { vm.mayShowChat }
                withContext(Dispatchers.Main) { vm.openConversation("A") }
                withTimeout(5000) { historyStarted.await() }
                withContext(Dispatchers.Main) { vm.editDraft("delivered"); vm.send("delivered") {} }
                awaitState { vm.operationState(Operation.Send).status == OperationStatus.Succeeded }
                withContext(Dispatchers.Main) {
                    assertNull(vm.error)
                    assertEquals("", vm.draftText)
                    assertFalse(vm.messages.single().pending)
                }
                assertEquals(1, posts.get())
                assertEquals("A committed send must not synchronously perform auth/profile refresh", 1, f.server.requestCount)
                // The independent background refresh can fail without changing the acknowledged operation.
                f.repo.api.updateUser(JSONObject(f.user.toString()).put("must_change_password", false))
                assertTrue(runCatching { f.repo.sync() }.exceptionOrNull() is ApiException)
                withContext(Dispatchers.Main) {
                    assertEquals(OperationStatus.Succeeded, vm.operationState(Operation.Send).status)
                    assertNull(vm.error)
                    assertFalse(vm.messages.single().pending)
                }
            } finally { withContext(Dispatchers.Main) { store.clear() } }
        }
    }

    @Test fun tokenRefreshStorageFailureIsNotHiddenAsNetworkUncertainty() = runBlocking {
        fixture { f ->
            val writes = AtomicInteger()
            val credentials = SecureSessionStore(f.secure) { writes.incrementAndGet(); throw java.io.IOException("disk fixture unavailable") }
            val api = Api(f.secure, f.server.url("/").toString().trimEnd('/'), credentials)
            val repo = Repository(f.context, { f.db }, f.secure, api)
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v1/conversations/A/messages" -> MockResponse().setResponseCode(401).setHeader("X-Auth-Reason", "expired")
                    "/api/v1/auth/refresh" -> MockResponse().setBody(JSONObject().put("access_token", "renewed-local-token")
                        .put("refresh_token", "renewed-local-refresh").put("user", f.user).toString())
                    else -> MockResponse().setResponseCode(404)
                }
            }
            try {
                repo.initialize()
                val failure = runCatching { repo.send("A", "credential storage fixture") }.exceptionOrNull()
                assertTrue(failure is CredentialStorageException)
                assertTrue(failure?.cause is java.io.IOException)
                assertEquals("无法保存登录凭据，请检查设备存储空间后重试", failure?.message)
                assertEquals(PendingDelivery.Failed, repo.messages("A").single().pendingDelivery)
                assertEquals("local-delivery-token", api.session?.getString("access_token"))
                assertEquals(1, writes.get())
                assertEquals(2, f.server.requestCount)
                val cancellation = CancellationException("cancelled credential write")
                val cancelled = Api(f.secure, f.server.url("/").toString().trimEnd('/'), SecureSessionStore(f.secure) { throw cancellation })
                assertSame(cancellation, runCatching { cancelled.save(null) }.exceptionOrNull())
            } finally { repo.stop() }
        }
    }

    private class Fixture(val app: TouchApp, val context: ContextWrapper, val secure: SecureStore,
                          val db: TouchDatabase, val repo: Repository, val server: MockWebServer, val user: JSONObject) {
        fun ack(request: RecordedRequest): JSONObject {
            val body = JSONObject(request.body.clone().readUtf8())
            return JSONObject().put("id", "accepted-${body.getString("client_id")}").put("conversation_id", "A")
                .put("sender_id", user.getString("id")).put("client_id", body.getString("client_id"))
                .put("seq", 1).put("kind", "text").put("text", body.optString("text"))
                .put("created_at", System.currentTimeMillis() / 1000)
        }
        fun dispatch(post: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = post(request)
        }
    }

    private suspend fun fixture(mustChangePassword: Boolean = false, block: suspend (Fixture) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val name = "delivery-${UUID.randomUUID()}.db"
        val folder = File(app.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = folder }
        val secure = SecureStore(context)
        val priorSession = secure.read("session"); val priorPrivacy = secure.read("privacy")
        val marker = File(app.filesDir, "privacy.enabled")
        val priorMarker = marker.takeIf(File::exists)?.readBytes()
        val db = withContext(Dispatchers.IO) { EncryptedDatabase.open(context, app.vault, name) }
        MockWebServer().use { server ->
            server.start()
            val user = JSONObject().put("id", UUID.randomUUID().toString()).put("username", "delivery-local")
                .put("display_name", "消息发送测试").put("must_change_password", mustChangePassword)
            val repo = Repository(context, { db }, secure, Api(secure, server.url("/").toString().trimEnd('/')))
            try {
                withContext(Dispatchers.IO) {
                    db.cache().put(TouchDatabase.Item("meta", "owner", user.getString("id")))
                    secure.write("session", JSONObject().put("access_token", "local-delivery-token").put("refresh_token", "local-refresh").put("user", user).toString())
                    secure.write("privacy", JSONObject().put("enabled", false).toString())
                    check(!marker.exists() || marker.delete())
                    repo.initialize()
                }
                block(Fixture(app, context, secure, db, repo, server, user))
            } finally {
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
