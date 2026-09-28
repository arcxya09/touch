package com.arcxya09.touch

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in: only disposable verify_ accounts. Peer credentials never enter app storage. */
@RunWith(AndroidJUnit4::class)
class ProfileInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun profilesRetentionAndReadIndicatorRespectRole() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val username = args.getString("touchTestUser")
        val peer = args.getString("touchPeerUser")
        assumeTrue(username?.startsWith("verify_") == true && peer?.startsWith("verify_") == true)
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val repo = app.repository
        repo.logout(false)
        app.secureStore.write("privacy", null)
        File(app.filesDir, "privacy.enabled").delete()
        repo.login(username!!, args.getString("touchTestPassword")!!)
        repo.enableRetention(false)
        repo.sync()
        val admin = repo.api.user!!.isAdmin
        if (admin) repo.setReadReceipts(false)
        val cid = repo.conversations().single().id
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(20000) { vm.initialized && vm.mayShowChat && vm.user != null }
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithContentDescription("本地定时销毁开关").assertIsOff()
            compose.onNodeWithText("编辑个人资料").performClick()
            compose.onNodeWithText("昵称").performTextReplacement("资料验收昵称")
            compose.onNodeWithText("个人简介").performTextReplacement("资料验收简介")
            compose.onNodeWithText("保存资料").performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithText("个人资料已保存").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("知道了").performClick()
            assertEquals("资料验收昵称", repo.api.user!!.name)
            assertEquals("资料验收简介", repo.api.user!!.bio)
            // A fixture image URI substitutes only for the system picker result.
            val avatar = File(app.cacheDir, "test-avatar.png")
            avatar.outputStream().use { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
            scenario.onActivity { vm.pendingAvatar = Uri.fromFile(avatar) }
            compose.onNodeWithText("上传头像").performClick()
            compose.waitUntil(20000) { !vm.busy && vm.user?.avatarVersion != null }
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("资料验收昵称的头像").fetchSemanticsNodes().isNotEmpty() }
            assertNotNull(repo.avatarBytes(repo.api.user!!))
            avatar.delete()
            compose.onNodeWithText("移除头像").performClick()
            compose.waitUntil(20000) { !vm.busy && vm.user?.avatarVersion == null }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("本地定时销毁开关").performClick()
            compose.onNodeWithText("确认").performClick()
            compose.waitUntil(15000) { !vm.busy && vm.retentionEnabled }
            assertEquals(3600L, vm.retentionSeconds)
            compose.onNodeWithContentDescription("本地定时销毁开关").performClick()
            compose.waitUntil(15000) { !vm.busy && !vm.retentionEnabled }
            if (admin) {
                compose.onNodeWithContentDescription("显示已读标识开关").performScrollTo().assertIsOff().performClick()
                compose.waitUntil(15000) { !vm.busy && vm.user?.readReceipts == true }
            } else compose.onNodeWithContentDescription("显示已读标识开关").assertDoesNotExist()
            scenario.onActivity { vm.openConversation(cid) }
            compose.waitUntil(15000) { !vm.busy && vm.screen == "chat" }
            val marker = "已读验收-${System.nanoTime()}"
            scenario.onActivity { vm.send(marker) {} }
            compose.waitUntil(20000) { !vm.busy && vm.messages.any { it.text == marker && !it.pending } }
            val sent = vm.messages.single { it.text == marker }
            compose.onNodeWithTag("read-${sent.id}", useUnmergedTree = true).assertDoesNotExist()
            val seq = sent.seq
            val client = OkHttpClient()
            fun post(path: String, json: JSONObject, token: String? = null): JSONObject {
                val request = Request.Builder().url(BuildConfig.API_BASE + path)
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                token?.let { request.header("Authorization", "Bearer $it") }
                return client.newCall(request.build()).execute().use { response ->
                    check(response.isSuccessful); JSONObject(response.body!!.string())
                }
            }
            val session = post("/api/v1/auth/login", JSONObject().put("username", peer).put("password", args.getString("touchPeerPassword")))
            post("/api/v1/conversations/$cid/read", JSONObject().put("seq", seq), session.getString("access_token"))
            if (admin) {
                compose.waitUntil(25000) { compose.onAllNodesWithTag("read-${sent.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                scenario.onActivity { vm.setReadReceipts(false) }
                compose.waitUntil(15000) { !vm.busy && vm.user?.readReceipts == false }
            } else {
                repo.sync()
                assertTrue(repo.conversations().all { it.peerReadSeq == 0L })
            }
            compose.onAllNodesWithContentDescription("对方已读", useUnmergedTree = true).assertCountEquals(0)
        }
        repo.logout(false)
    }
}
