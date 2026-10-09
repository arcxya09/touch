package com.arcxya09.touch

import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.Api
import com.arcxya09.touch.data.ApiException
import com.arcxya09.touch.data.EncryptedDatabase
import com.arcxya09.touch.data.Repository
import com.arcxya09.touch.data.TouchDatabase
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A confirmed account change must not depend on an unrelated, later full synchronization. */
class ProfileOperationTest {
    @Test fun confirmedChangesStaySuccessfulWhenTheNextSyncIsUnavailable() = runBlocking {
        fixture { vm, repo, server, user, folder ->
            val updated = JSONObject(user.toString()).put("display_name", "已保存的昵称")
                .put("bio", "已保存的简介").put("avatar_version", UUID.randomUUID().toString())
                .put("read_receipts_enabled", true)
            val avatar = File(folder, "avatar.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val mutations = listOf<Pair<String, () -> Unit>>(
                "PATCH /api/v1/auth/profile" to { vm.saveProfile("已保存的昵称", "已保存的简介") },
                "POST /api/v1/auth/avatar" to { vm.pendingAvatar = Uri.fromFile(avatar); vm.uploadAvatar() },
                "DELETE /api/v1/auth/avatar" to { vm.removeAvatar() },
                "PATCH /api/v1/auth/preferences" to { vm.setReadReceipts(true) },
            )
            for ((expected, mutate) in mutations) {
                val result = JSONObject(updated.toString()).apply {
                    if (expected.startsWith("DELETE")) put("avatar_version", JSONObject.NULL)
                }
                server.enqueue(MockResponse().setBody(result.toString()))
                // The old implementation consumed this as part of the same operation and reported failure.
                server.enqueue(MockResponse().setResponseCode(503).setBody("{\"detail\":\"sync temporarily unavailable\"}"))
                val before = server.requestCount
                withContext(Dispatchers.Main) { mutate() }
                awaitState { !vm.isWorking(Operation.Profile) }
                withContext(Dispatchers.Main) {
                    assertEquals(expected, OperationStatus.Succeeded, vm.operationState(Operation.Profile).status)
                    assertNull(vm.error)
                    assertEquals(result.getString("display_name"), vm.user?.name)
                    assertEquals(result.optString("avatar_version").takeUnless { it == "null" }, vm.user?.avatarVersion)
                    assertEquals(result.getBoolean("read_receipts_enabled"), vm.user?.readReceipts)
                    assertNull(vm.pendingAvatar)
                }
                assertEquals("The mutation must finish without a full sync", before + 1, server.requestCount)
                val mutation = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Missing profile mutation")
                assertEquals(expected, "${mutation.method} ${mutation.path}")
                val saved = JSONObject(repo.api.session.toString()).getJSONObject("user")
                assertEquals(result.toString(), saved.toString())
                val failure = runCatching { repo.sync() }.exceptionOrNull()
                assertTrue(failure is ApiException && failure.status == 503)
                assertEquals("/api/v1/auth/me", server.takeRequest(2, TimeUnit.SECONDS)?.path)
                withContext(Dispatchers.Main) {
                    assertEquals(OperationStatus.Succeeded, vm.operationState(Operation.Profile).status)
                    assertNull(vm.error)
                    assertEquals(result.getString("display_name"), vm.user?.name)
                }
            }
        }
    }

    @Test fun rejectedChangeStillReportsFailureAndRevokedSessionSignsOut() = runBlocking {
        fixture { vm, repo, server, user, _ ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"detail\":\"昵称不能为空\"}"))
            withContext(Dispatchers.Main) { vm.saveProfile("", "") }
            awaitState { !vm.isWorking(Operation.Profile) }
            withContext(Dispatchers.Main) {
                assertEquals(OperationStatus.Failed, vm.operationState(Operation.Profile).status)
                assertEquals("昵称不能为空", vm.error)
                assertEquals(user.getString("display_name"), vm.user?.name)
            }
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"detail\":\"登录已失效\"}"))
            withContext(Dispatchers.Main) { vm.removeAvatar() }
            awaitState { !vm.isWorking(Operation.Profile) && vm.user == null }
            assertNull(repo.api.session)
            withContext(Dispatchers.Main) { assertEquals(OperationStatus.Failed, vm.operationState(Operation.Profile).status) }
        }
    }

    @Test fun cancellingAnUnconfirmedChangeDoesNotPublishSuccess() = runBlocking {
        fixture { vm, repo, server, user, _ ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody(JSONObject(user.toString()).put("display_name", "取消的修改").toString())
                }
            }
            try {
                withContext(Dispatchers.Main) { vm.saveProfile("取消的修改", "") }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) { vm.background() }
            } finally { release.countDown() }
            awaitState { !vm.isWorking(Operation.Profile) }
            withContext(Dispatchers.Main) {
                assertEquals(OperationStatus.Idle, vm.operationState(Operation.Profile).status)
                assertEquals(user.getString("display_name"), vm.user?.name)
                assertNull(vm.notice)
            }
            assertEquals(user.getString("display_name"), repo.api.user?.name)
        }
    }

    @Test fun explicitSelectionCancellationStopsOnlyItsUploadAndKeepsOtherRequestsAlive() = runBlocking {
        for (avatar in listOf(false, true)) fixture { vm, repo, server, user, folder ->
            val uploadEntered = CountDownLatch(1)
            val otherEntered = CountDownLatch(1)
            val releaseUpload = CountDownLatch(1)
            val releaseOther = CountDownLatch(1)
            val operation = if (avatar) Operation.Profile else Operation.Attachment
            val otherOperation = if (avatar) Operation.Attachment else Operation.Profile
            val source = File(folder, "selection-fixture.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val uri = Uri.fromFile(source)
            val uploadResult = if (avatar) JSONObject(user.toString()).put("avatar_version", "cancelled-avatar")
                else JSONObject().put("id", "cancelled-file").put("name", source.name).put("kind", "file")
                    .put("mime", "application/octet-stream").put("size", 3).put("sha256", "0".repeat(64))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/unrelated" -> {
                        otherEntered.countDown()
                        check(releaseOther.await(10, TimeUnit.SECONDS))
                        MockResponse().setBody("{\"ok\":true}")
                    }
                    request.path == "/api/v1/auth/avatar" || request.path?.startsWith("/api/v1/files?") == true -> {
                        uploadEntered.countDown()
                        check(releaseUpload.await(10, TimeUnit.SECONDS))
                        MockResponse().setBody(uploadResult.toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            try {
                withContext(Dispatchers.Main) { vm.action(otherOperation) { repo.api.json("/unrelated") } }
                assertTrue(withContext(Dispatchers.IO) { otherEntered.await(5, TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) {
                    if (avatar) { vm.navigate(Screen.Profile); vm.pendingAvatar = uri; vm.uploadAvatar() }
                    else { vm.openConversation("A"); vm.pendingSelection = uri to "file"; vm.sendSelection() }
                }
                assertTrue(withContext(Dispatchers.IO) { uploadEntered.await(5, TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) {
                    assertTrue(vm.isWorking(operation))
                    vm.cancelSelection(avatar)
                    assertEquals(OperationStatus.Idle, vm.operationState(operation).status)
                    assertTrue("Cancelling selection must preserve the other operation", vm.isWorking(otherOperation))
                    assertNull(if (avatar) vm.pendingAvatar else vm.pendingSelection)
                    assertNull(vm.transfer)
                }
                releaseOther.countDown()
                awaitState { !vm.isWorking(otherOperation) }
                releaseUpload.countDown()
                withTimeout(5000) { while (repo.api.client.dispatcher.runningCallsCount() > 0) delay(10) }
                withContext(Dispatchers.Main) {
                    assertEquals(OperationStatus.Succeeded, vm.operationState(otherOperation).status)
                    assertEquals(OperationStatus.Idle, vm.operationState(operation).status)
                    assertNull(vm.error)
                    assertNull(vm.user?.avatarVersion)
                }
                assertTrue(withContext(Dispatchers.IO) { repo.db.cache().pendingItems().isEmpty() })
                assertEquals("No message should be submitted after its upload was cancelled", 2, server.requestCount)
            } finally { releaseUpload.countDown(); releaseOther.countDown() }
        }
    }

    private suspend fun fixture(block: suspend (AppViewModel, Repository, MockWebServer, JSONObject, File) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val name = "profile-operation-${UUID.randomUUID()}.db"
        val folder = File(app.cacheDir, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = folder }
        val secure = SecureStore(context)
        val priorSession = secure.read("session")
        val priorPrivacy = secure.read("privacy")
        val marker = File(app.filesDir, "privacy.enabled")
        val priorMarker = marker.takeIf(File::exists)?.readBytes()
        val store = ViewModelStore()
        val db = withContext(Dispatchers.IO) { EncryptedDatabase.open(context, app.vault, name) }
        MockWebServer().use { server ->
            server.start()
            val owner = UUID.randomUUID().toString()
            val user = JSONObject().put("id", owner).put("username", "profile-fixture")
                .put("display_name", "原昵称").put("bio", "原简介").put("is_admin", true)
            val repo = Repository(context, { db }, secure, Api(secure, server.url("/").toString().trimEnd('/')))
            try {
                withContext(Dispatchers.IO) {
                    db.cache().put(TouchDatabase.Item("meta", "owner", owner))
                    secure.write("session", JSONObject().put("access_token", "local-profile-token")
                        .put("refresh_token", "local-profile-refresh").put("user", user).toString())
                    secure.write("privacy", JSONObject().put("enabled", false).toString())
                    check(!marker.exists() || marker.delete())
                    repo.initialize()
                }
                val vm = withContext(Dispatchers.Main) { AppViewModel(app, repo).also { store.put("profile", it) } }
                // Keep the ViewModel out of the resumed lifecycle so only explicit fixture operations use HTTP.
                awaitState { vm.initialized && vm.mayShowChat && vm.user != null }
                block(vm, repo, server, user, folder)
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

    private suspend fun awaitState(check: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { check() }) delay(10)
    }
}
