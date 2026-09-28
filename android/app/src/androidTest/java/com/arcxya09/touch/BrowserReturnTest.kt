package com.arcxya09.touch

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class BrowserReturnTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun webLinkLeavesAppAndReturnRequiresUnlock() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("touchTestUser")
        assumeTrue(name?.startsWith("verify_") == true)
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val repo = app.repository
        repo.logout(false)
        repo.login(name!!, args.getString("touchTestPassword")!!)
        repo.sync()
        val cid = repo.conversations().first().id
        repo.send(cid, "https://example.com")
        repo.sync()
        val message = repo.messages(cid).last { it.text == "https://example.com" }
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", "0125").toString())
        File(app.filesDir, "privacy.enabled").writeText("1")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(10000) { vm.initialized && vm.privacy }
            scenario.onActivity { vm.unlock(listOf(0, 1, 2, 5)); vm.openConversation(cid) }
            compose.waitUntil(15000) { vm.mayShowChat && !vm.busy && vm.messages.any { it.id == message.id } }
            compose.onNodeWithTag("message-text-${message.id}").performTouchInput { click(center) }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(device.wait(Until.gone(By.pkg(app.packageName).depth(0)), 10000))
            args.getString("expectedBrowserPackage")?.let { browser ->
                assertTrue(device.wait(Until.hasObject(By.pkg(browser)), 10000))
            }
            compose.waitUntil(10000) { vm.locked && !vm.mayShowChat }
            device.pressBack()
            if (!device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), 3000)) {
                // A browser's first-run UI can exit to the launcher; reopen Touch explicitly.
                app.startActivity(app.packageManager.getLaunchIntentForPackage(app.packageName))
            }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("timer-screen").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("message-text-${message.id}").assertDoesNotExist()
            scenario.onActivity { vm.unlock(listOf(0, 1, 2, 5)) }
            compose.waitUntil(10000) { vm.mayShowChat }
            compose.onNodeWithTag("message-text-${message.id}").assertExists()
        }
    }
}
