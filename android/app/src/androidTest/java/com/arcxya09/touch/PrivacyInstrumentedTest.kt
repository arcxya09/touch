package com.arcxya09.touch

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

@RunWith(AndroidJUnit4::class)
class PrivacyInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<TouchApp>()
    private var scenario: ActivityScenario<MainActivity>? = null
    @Before fun prepare() = runBlocking<Unit> {
        app.repository.logout(false)
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", "0125").toString())
        File(app.filesDir, "privacy.enabled").writeText("1")
        app.getSharedPreferences("gesture_attempts", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { scenario?.close() }
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
    @Test fun syncRowsAndCursorRollbackTogether() {
        val cache = app.database.cache()
        try {
            app.database.runInTransaction {
                cache.put(TouchDatabase.Item("message", "test", "{}"))
                cache.put(TouchDatabase.Item("meta", "cursor", "100"))
                throw IllegalStateException("simulated crash before commit")
            }
        } catch (_: IllegalStateException) { }
        assertNull(cache.get("message", "test"))
        assertNull(cache.get("meta", "cursor"))
    }
}
