package com.arcxya09.touch

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.arcxya09.touch.notifications.AlertService
import com.arcxya09.touch.security.SafetyOptions
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ConnectionLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<TouchApp>()
    private val repo get() = app.repository
    private val args get() = InstrumentationRegistry.getArguments()
    private val client = OkHttpClient()
    private fun post(path: String, json: JSONObject, token: String? = null): JSONObject {
        val request = Request.Builder().url(BuildConfig.API_BASE + path)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
        token?.let { request.header("Authorization", "Bearer $it") }
        return client.newCall(request.build()).execute().use { check(it.isSuccessful); JSONObject(it.body!!.string()) }
    }
    private fun control(vararg values: Pair<String, Boolean>) = post("/__fixture/control", JSONObject().apply { values.forEach { put(it.first,it.second) } })
    private suspend fun prepare() {
        assumeTrue(BuildConfig.API_BASE == "http://127.0.0.1:18881")
        assumeTrue(args.getString("touchTestUser")?.startsWith("verify_local_") == true)
        control("http_fail" to false, "ws_reject" to false, "contacts_fail_once" to false, "delay_me" to false)
        repo.initialize(); repo.logout(false)
        repo.login(args.getString("touchTestUser")!!, args.getString("touchTestPassword")!!)
        repo.enableRetention(false); repo.sync()
        app.secureStore.write("privacy", JSONObject().put("enabled",false).toString())
        File(app.filesDir,"privacy.enabled").delete(); SafetyOptions().save(app.vault)
    }
    @Test fun websocketFailureAndForegroundResumeCannotShowStaleLiveState() = runBlocking<Unit> {
        prepare()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: AppViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
                compose.waitUntil(20000) { vm.connected }
                control("ws_reject" to true, "close" to true)
                compose.waitUntil(10000) { !vm.connected }
                repo.sync() // HTTP still works, but realtime transport is absent.
                assertFalse(vm.connected)
                control("ws_reject" to false)
                repo.recheck()
                compose.waitUntil(30000) { vm.connected }
                scenario.moveToState(Lifecycle.State.CREATED)
                control("http_fail" to true)
                scenario.moveToState(Lifecycle.State.RESUMED)
                delay(1500); assertFalse(vm.connected)
                control("http_fail" to false); repo.recheck()
                compose.waitUntil(30000) { vm.connected }
                scenario.onActivity { vm.enableAlerts(true) }
                compose.waitUntil(20000) { AlertService.running && AlertService.connected && !vm.busy }
                // A foreground round trip must sync without replacing the service-owned socket.
                val generation = repo.connection.state.value.generation
                val reconnects = repo.connection.reconnects
                scenario.moveToState(Lifecycle.State.CREATED)
                delay(100)
                val lastSync = repo.connection.lastSyncWall
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(20000) { vm.connected && repo.connection.lastSyncWall != lastSync }
                assertEquals(generation, repo.connection.state.value.generation)
                assertEquals(reconnects, repo.connection.reconnects)
                scenario.moveToState(Lifecycle.State.CREATED)
                control("http_fail" to true, "ws_reject" to true, "close" to true)
                compose.waitUntil(10000) { !AlertService.connected }
                scenario.moveToState(Lifecycle.State.RESUMED)
                delay(1500); assertFalse(vm.connected)
                control("http_fail" to false,"ws_reject" to false)
                repo.recheck(); AlertService.wake()
                compose.waitUntil(30000) { vm.connected && AlertService.connected }
            }
        } finally {
            control("http_fail" to false,"ws_reject" to false)
            app.alertSettings.disable(); withContext(Dispatchers.Main) { AlertService.stop(app) }
        }
    }
    @Test fun failedFinalSyncDoesNotConsumeCursorAndRefreshRaceDoesNotRevokeSession() = runBlocking<Unit> {
        prepare()
        val peer = post("/api/v1/auth/login", JSONObject().put("username",args.getString("touchPeerUser")).put("password",args.getString("touchPeerPassword")))
        val cid = repo.conversations().single().id
        val cursor = withContext(Dispatchers.IO) { app.database.cache().get("meta","cursor")!!.json }
        val text = "partial-sync-" + UUID.randomUUID()
        post("/api/v1/conversations/$cid/messages",JSONObject().put("client_id",UUID.randomUUID().toString()).put("kind","text").put("text",text),peer.getString("access_token"))
        var notified = false
        repo.onIncoming = { if (it.any { message -> message.text == text }) notified = true }
        try {
            control("contacts_fail_once" to true)
            assertTrue(runCatching { repo.sync() }.isFailure)
            assertEquals(cursor,withContext(Dispatchers.IO) { app.database.cache().get("meta","cursor")!!.json })
            assertFalse(notified)
            repo.sync(); assertTrue(notified)
            val old = repo.api.session!!.getString("access_token")
            control("expire" to true,"delay_me" to true)
            coroutineScope {
                val slow = async { repo.api.json("/api/v1/auth/me") }
                delay(100)
                val fast = async { repo.api.text("/api/v1/contacts") }
                fast.await(); slow.await()
            }
            assertNotEquals(old,repo.api.session!!.getString("access_token"))
            assertNotNull(repo.api.user)
            repo.sync()
        } finally { repo.onIncoming = null; control("delay_me" to false,"contacts_fail_once" to false) }
    }
}
