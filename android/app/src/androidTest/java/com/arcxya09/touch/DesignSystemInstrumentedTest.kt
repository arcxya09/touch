package com.arcxya09.touch

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.arcxya09.touch.timer.TimerState
import com.arcxya09.touch.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Reproducible screenshots use synthetic data and do not connect to a server. */
class DesignSystemInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @Test fun threeKeyScreensRenderLightDarkAndRecoveryFixturesOffline() {
        var screen by mutableStateOf(DesignFixture.Timer)
        var dark by mutableStateOf(false)
        var error by mutableStateOf(false)
        compose.setContent { DesignFixtureScreen(screen, dark, error) }
        DesignFixture.entries.forEach { fixture ->
            listOf(false, true).forEach { night ->
                listOf(false, true).forEach { recovery ->
                    compose.runOnIdle { screen = fixture; dark = night; error = recovery }
                    compose.waitForIdle()
                    saveScreenshot("${fixture.name.lowercase()}-${if (night) "dark" else "light"}-${if (recovery) "recovery" else "ready"}")
                }
            }
        }
    }
    @Test fun timerActionsRemainReachableInSmallLargeTextAndDarkLayouts() {
        var dark by mutableStateOf(false)
        var large by mutableStateOf(false)
        var started = 0
        compose.setContent {
            val base = LocalDensity.current
            // A new tree keeps the preceding theme's native ripple and scroll state out of the next sample.
            key(dark, large) {
                CompositionLocalProvider(LocalDensity provides Density(base.density, if (large) 2f else 1f)) {
                    TouchTheme(darkTheme = dark) {
                        Surface(Modifier.requiredSize(320.dp, 640.dp)) {
                            TimerContent(TimerState(25, 5, false, false, 25 * 60000L, false), {}, { started++ }, {}, {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("开始").performScrollTo().assertIsDisplayed()
        // RenderThread ripple animation is not governed by Compose's test clock. Capture the
        // untouched button, then verify its click, rather than recording a nondeterministic ripple.
        saveScreenshot("timer-light-320")
        compose.onNodeWithText("开始").performClick()
        compose.runOnIdle { assertEquals(1, started) }
        compose.runOnIdle { dark = true; large = true }
        compose.onNodeWithText("时长").assertIsDisplayed()
        compose.onNodeWithText("开始").performScrollTo().assertIsDisplayed()
        saveScreenshot("timer-dark-font200-320")
        compose.onNodeWithText("开始").performClick()
        compose.runOnIdle { assertEquals(2, started) }
    }
    @Test fun messageMenuAndLinksExposeIndependentAccessibleActions() {
        var menu = 0
        var opened: String? = null
        compose.setContent {
            TouchTheme { MessageText("参考 https://example.com", { opened = it }, onLongPress = { menu++ },
                messageActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction("引用回复") { menu++; true })) }
        }
        val node = compose.onNodeWithText("参考 https://example.com").fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("引用回复", "打开链接 https://example.com"), actions.map { it.label })
        compose.runOnIdle { assertTrue(actions.first().action()); assertEquals(1, menu); assertNull(opened) }
        compose.runOnIdle { assertTrue(actions.last().action()); assertEquals("https://example.com", opened) }
    }
    @Test fun settingsToggleHasOneAccessibleSwitchAndWholeRowAction() {
        var checked by mutableStateOf(false)
        compose.setContent { TouchTheme { Surface { ToggleSetting("本机加密草稿", "仅本机保存", checked, onChange = { checked = it }) } } }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNodeWithContentDescription("本机加密草稿开关").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(checked) }
    }
    private fun saveScreenshot(name: String) {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "design-snapshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
