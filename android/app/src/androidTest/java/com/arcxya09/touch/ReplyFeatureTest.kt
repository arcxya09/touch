package com.arcxya09.touch

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.arcxya09.touch.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReplyFeatureTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun offlineQueueClearsSavedDraftOnlyAfterCommitAndNeedsManualRetry() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.API_BASE == "http://127.0.0.1:18881")
        assumeTrue(args.getString("touchTestUser")?.startsWith("verify_local_") == true)
        val repo = ApplicationProvider.getApplicationContext<TouchApp>().repository
        repo.logout(false); repo.login(args.getString("touchTestUser")!!, args.getString("touchTestPassword")!!)
        repo.enableRetention(false); repo.sync()
        val cid = repo.conversations().first().id
        repo.setDraftEnabled(true)
        repo.saveDraft(cid, "offline-109", null, System.currentTimeMillis() / 1000)
        repo.api.json("/__fixture/control", "POST", JSONObject().put("http_fail", true))
        try {
            assertTrue(runCatching { repo.send(cid, "offline-109") }.isFailure)
            assertNull(repo.draft(cid))
            assertEquals(1, repo.messages(cid).count { it.pending && it.text == "offline-109" })
        } finally { repo.api.json("/__fixture/control", "POST", JSONObject().put("http_fail", false)) }
        repo.sync()
        val pending = repo.messages(cid).single { it.pending && it.text == "offline-109" }
        repo.retry(pending.id); repo.retry(pending.id); repo.sync()
        assertEquals(1, repo.messages(cid).count { it.clientId == pending.clientId && !it.pending })
        repo.logout(false)
    }
    @Test fun quoteMenuEncryptedDraftSendingAndExpiredReference() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("touchTestUser")
        assumeTrue(name?.startsWith("verify_") == true)
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val repo = app.repository
        repo.logout(false); repo.enableRetention(false)
        app.secureStore.write("privacy", JSONObject().put("enabled", false).toString())
        File(app.filesDir, "privacy.enabled").delete()
        repo.login(name!!, args.getString("touchTestPassword")!!); repo.sync()
        val cid = repo.conversations().first().id
        repo.send(cid, "quote-original-109")
        repo.sync()
        assertFalse(repo.draftEnabled())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(15000) { vm.mayShowChat }
            scenario.onActivity { vm.openConversation(cid) }
            compose.waitUntil(15000) { !vm.busy && vm.messages.any { it.text == "quote-original-109" } }
            val original = vm.messages.last { it.text == "quote-original-109" }
            compose.onNodeWithTag("message-text-${original.id}").performTouchInput { longClick() }
            compose.onNodeWithText("引用回复").performClick()
            assertEquals(original.id, vm.quote?.id)
            compose.onNodeWithContentDescription("取消引用").performClick()
            assertNull(vm.quote)
            scenario.onActivity { vm.enableDrafts(true) }
            compose.waitUntil(10000) { !vm.busy && vm.saveDrafts }
            scenario.onActivity { vm.quoteMessage(original); vm.editDraft("encrypted-draft-109") }
            compose.waitUntil(5000) { runBlocking { repo.draft(cid)?.optString("text") == "encrypted-draft-109" } }
            val saved = repo.draft(cid)!!
            assertEquals(original.id, saved.getJSONObject("reply_to").getString("id"))
            assertFalse(saved.toString().contains("quote-original-109"))
            assertFalse(app.getDatabasePath("touch.db").readBytes().toString(Charsets.ISO_8859_1).contains("encrypted-draft-109"))
            scenario.recreate()
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(10000) { vm.mayShowChat }
            scenario.onActivity { vm.openConversation(cid) }
            compose.waitUntil(10000) { !vm.busy && vm.draftText == "encrypted-draft-109" }
            assertEquals(original.id, vm.quote?.id)
            scenario.onActivity { vm.send(vm.draftText) {} }
            compose.waitUntil(15000) { !vm.busy && vm.messages.any { it.text == "encrypted-draft-109" && !it.pending } }
            assertTrue(vm.draftText.isEmpty()); assertNull(repo.draft(cid))
            val reply = vm.messages.last { it.text == "encrypted-draft-109" }
            assertEquals(original.id, reply.replyTo?.id)
            scenario.onActivity { vm.locate(reply.replyTo!! ) }
            compose.waitUntil(10000) { !vm.busy && vm.browsingHistory }
            assertTrue(vm.messages.any { it.id == original.id })
            assertTrue(vm.messages.size <= 50)
            // Expired references cannot be rehydrated, and a failed preflight must retain input.
            scenario.onActivity { vm.quoteMessage(original.copy(createdAt = 1)); vm.editDraft("keep-after-invalid-reference"); vm.enableRetention(true) }
            compose.waitUntil(10000) { !vm.busy && vm.retentionEnabled }
            scenario.onActivity { vm.send(vm.draftText) {} }
            compose.waitUntil(10000) { !vm.busy && vm.error != null }
            assertEquals("keep-after-invalid-reference", vm.draftText)
            assertNull(repo.original(cid, ReplyRef(original.id, original.seq, 1)))
            scenario.onActivity { vm.error = null; vm.cancelQuote(); vm.enableDrafts(false) }
            compose.waitUntil(10000) { !vm.busy && !vm.saveDrafts }
            assertNull(repo.draft(cid))
        }
        repo.logout(false); repo.enableRetention(false)
    }
}
