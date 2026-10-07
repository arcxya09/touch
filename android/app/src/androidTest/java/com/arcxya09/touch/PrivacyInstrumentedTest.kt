package com.arcxya09.touch

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arcxya09.touch.data.TouchDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI

@RunWith(AndroidJUnit4::class)
class PrivacyInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<TouchApp>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousRotationLock: Boolean? = null
    private var previousImageRotation: Boolean? = null
    @Before fun prepare() = runBlocking<Unit> {
        val preferences = app.getSharedPreferences("display_preferences", Context.MODE_PRIVATE)
        previousRotationLock = if (preferences.contains("lock_chat_rotation")) preferences.getBoolean("lock_chat_rotation", false) else null
        previousImageRotation = if (preferences.contains("rotate_image_preview")) preferences.getBoolean("rotate_image_preview", false) else null
        preferences.edit().remove("lock_chat_rotation").remove("rotate_image_preview").commit()
        app.repository.logout(false)
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", "0125").toString())
        File(app.filesDir, "privacy.enabled").writeText("1")
        app.getSharedPreferences("gesture_attempts", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() {
        try { scenario?.close() } finally {
            val edit = app.getSharedPreferences("display_preferences", Context.MODE_PRIVATE).edit()
            previousRotationLock?.let { edit.putBoolean("lock_chat_rotation", it) } ?: edit.remove("lock_chat_rotation")
            previousImageRotation?.let { edit.putBoolean("rotate_image_preview", it) } ?: edit.remove("rotate_image_preview")
            edit.commit()
        }
    }
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("hidden-pattern").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun unlock() {
        compose.onNodeWithTag("hidden-pattern").performTouchInput {
            down(Offset(width / 6f, height / 6f))
            moveTo(Offset(width / 2f, height / 6f), 100)
            moveTo(Offset(width * 5f / 6f, height / 6f), 100)
            moveTo(Offset(width * 5f / 6f, height / 2f), 100)
            up()
        }
    }
    private fun activeModel(): AppViewModel {
        lateinit var model: AppViewModel
        scenario!!.onActivity {
            model = ViewModelProvider(it)[AppViewModel::class.java]
            // These navigation tests use only the disposable local API, never the update service.
            model.updater = null
        }
        return model
    }
    private fun assertRequestedOrientation(expected: Int, reason: String) {
        compose.waitForIdle()
        compose.waitUntil(10000) {
            var current = Int.MIN_VALUE
            scenario!!.onActivity { current = it.requestedOrientation }
            current == expected
        }
        scenario!!.onActivity { assertEquals(reason, expected, it.requestedOrientation) }
    }
    @Test fun rotationLockAlsoCoversLockedTimerLoginAndRecreation() {
        app.getSharedPreferences("display_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("lock_chat_rotation", true).putBoolean("rotate_image_preview", true).commit()
        launch()
        val model = activeModel()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "The locked timer must keep the global portrait lock")
        unlock()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "The signed-out login page must keep the global portrait lock")
        scenario!!.onActivity { model.navigate(Screen.Timer) }
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "The explicitly opened timer must also be locked")
        scenario!!.recreate()
        val recreated = activeModel()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Recreation must retain the preference while relocking privacy")
        scenario!!.onActivity { recreated.setRotationLocked(false) }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, "Disabling the lock must release even the locked timer")
        unlock()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, "Disabling the lock must release login as well")
    }
    @Test fun rotationLockCoversEveryPageAndOnlyVisibleImagesCanOptOut() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val apiUri = URI(BuildConfig.API_BASE)
        require(apiUri.scheme == "http" && apiUri.host == "127.0.0.1") { "Use the disposable loopback device fixture for the orientation navigation test" }
        val username = requireNotNull(args.getString("touchTestUser"))
        require(username.startsWith("verify_local_")) { "Only disposable fixture accounts are allowed" }
        app.repository.initialize()
        app.repository.login(username, requireNotNull(args.getString("touchTestPassword")))
        app.repository.sync()
        val cid = app.repository.conversations().single().id
        app.repository.history(cid)
        val rows = app.repository.messages(cid)
        val image = rows.first { it.file?.kind == "image" }
        val document = rows.first { it.file?.mime == "application/pdf" }
        app.repository.download(image) { }
        app.repository.download(document) { }
        app.getSharedPreferences("display_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("lock_chat_rotation", true).commit()
        launch()
        val model = activeModel()
        unlock()
        compose.waitUntil(15000) { model.mayShowChat && model.user != null }
        scenario!!.onActivity { model.openConversation(cid) }
        compose.waitUntil(15000) { model.destination == Screen.Chat && !model.isWorking(Operation.Conversation) }
        for (screen in Screen.entries.filter { it != Screen.Preview }) {
            scenario!!.onActivity { model.navigate(screen) }
            assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Global lock must cover ${screen.route}")
            scenario!!.onActivity { assertEquals(screen, model.destination) }
        }
        fun showAttachment(message: com.arcxya09.touch.data.ChatMessage) {
            scenario!!.onActivity { model.navigate(Screen.Chat); model.openFile(message) }
            compose.waitUntil(15000) {
                model.destination == Screen.Preview && model.preview?.first?.id == message.file!!.id && !model.isWorking(Operation.Attachment)
            }
        }
        showAttachment(image)
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Images stay locked until their exception is enabled")
        scenario!!.onActivity { model.setImagePreviewRotation(true) }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, "A visible image is the only allowed rotation exception")
        scenario!!.onActivity { model.setImagePreviewRotation(false) }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Turning off the image exception restores portrait immediately")
        scenario!!.onActivity { model.setImagePreviewRotation(true); model.back() }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Leaving the image viewer must restore the global lock")
        scenario!!.onActivity { assertEquals(Screen.Chat, model.destination); assertNull(model.preview) }
        showAttachment(document)
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "A document cannot use the image rotation exception")
        scenario!!.onActivity { model.setRotationLocked(false) }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, "Disabling the global lock releases a document viewer")
        scenario!!.onActivity { model.setRotationLocked(true) }
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Re-enabling the lock restores the document viewer to portrait")
        showAttachment(image)
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, "The image exception remains available for the next image visit")
        scenario!!.onActivity { model.hide() }
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        assertRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "Privacy hiding must immediately end the image rotation exception")
        scenario!!.onActivity { assertTrue(model.locked); assertNull(model.preview) }
    }
    @Test fun coldStartHidesLoginUntilGesture() {
        launch()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        compose.onNodeWithText("欢迎回来").assertDoesNotExist()
        unlock()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        scenario!!.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
    }
    @Test fun backgroundAndRecreationAlwaysRelock() {
        launch(); unlock()
        compose.onNodeWithText("欢迎回来").assertIsDisplayed()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        compose.onNodeWithText("欢迎回来").assertDoesNotExist()
        unlock()
        scenario!!.recreate()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
    }
    @Test fun markerWithMissingConfigurationFailsClosed() = runBlocking<Unit> {
        app.secureStore.write("privacy", null)
        launch()
        compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        unlock()
        compose.onNodeWithText("欢迎回来").assertDoesNotExist()
    }
    @Test fun rotationReflowsWithoutRecreationAndBackgroundStillLocks() {
        launch(); unlock()
        var original: MainActivity? = null
        scenario!!.onActivity {
            original = it
        }
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
            compose.waitUntil(10000) {
                original!!.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            scenario!!.onActivity { assertSame(original, it) }
            compose.onNodeWithText("欢迎回来").assertIsDisplayed()
            scenario!!.moveToState(Lifecycle.State.CREATED)
            scenario!!.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("timer-screen").assertIsDisplayed()
        } finally {
            automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        }
    }
    @Test fun syncRowsAndCursorRollbackTogether() {
        val cache = app.database.cache()
        val previousCursor = cache.get("meta", "cursor")?.json
        val id = "rollback-" + java.util.UUID.randomUUID()
        try {
            app.database.runInTransaction {
                cache.put(TouchDatabase.Item("message", id, "{}"))
                cache.put(TouchDatabase.Item("meta", "cursor", "100"))
                throw IllegalStateException("simulated crash before commit")
            }
        } catch (_: IllegalStateException) { }
        assertNull(cache.get("message", id))
        assertEquals(previousCursor, cache.get("meta", "cursor")?.json)
    }
    @Test fun expiredTimerDoesNotRingOnColdStart() {
        app.getSharedPreferences("pomodoro", Context.MODE_PRIVATE).edit()
            .putBoolean("running", true).putLong("wallEnd", 1L).putInt("boot", -100).commit()
        launch()
        val state = com.arcxya09.touch.timer.Pomodoro(app).state()
        assertFalse(state.running)
        assertTrue(state.complete)
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.activeNotifications.none { it.id == 25 })
    }
}
