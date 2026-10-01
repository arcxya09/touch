package com.arcxya09.touch

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.arcxya09.touch.ui.BusyIndicator
import com.arcxya09.touch.ui.MessageText
import com.arcxya09.touch.ui.TouchTheme
import com.arcxya09.touch.ui.messageLinks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MessageInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun progressNeverMovesContent() {
        val busy = mutableStateOf(false)
        compose.setContent { TouchTheme { Column { BusyIndicator(busy.value); Text("content", Modifier.testTag("below")) } } }
        val before = compose.onNodeWithTag("below").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { busy.value = true }
        assertEquals(before, compose.onNodeWithTag("below").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { busy.value = false }
        assertEquals(before, compose.onNodeWithTag("below").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun identifiesWebAddressesAndPreservesOriginalOffsets() {
        val text = "链接 https://example.com/a?q=1&x=2，另一个 www.example.org。"
        val links = messageLinks(text)
        assertEquals(listOf("https://example.com/a?q=1&x=2", "https://www.example.org"), links.map { it.url })
        assertEquals(listOf("https://example.com/a?q=1&x=2", "www.example.org"), links.map { text.substring(it.start, it.end) })
        assertEquals("https://example.com/wiki/Test_(a)", messageLinks("(https://example.com/wiki/Test_(a))").single().url)
        assertTrue(messageLinks("hello user@example.com javascript:alert(1) intent://settings").isEmpty())
    }

    @Test fun messageActionsAndNativeSelectionAreMutuallyExclusive() {
        val selecting = mutableStateOf(false)
        var actions = 0
        compose.setContent { TouchTheme { Column(Modifier.padding(top = 64.dp)) {
            MessageText("copyme", {}, Modifier.testTag("message"),
                onLongPress = if (selecting.value) null else ({ actions++ }))
        } } }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val copy = By.text(java.util.regex.Pattern.compile("Copy|复制"))
        compose.onNodeWithTag("message").performTouchInput { longClick(center) }
        compose.runOnIdle { assertEquals(1, actions) }
        assertFalse(device.hasObject(copy))
        compose.runOnIdle { selecting.value = true }
        compose.onNodeWithTag("message").performTouchInput { longClick(center) }
        assertTrue(device.wait(Until.hasObject(copy), 5000))
        compose.runOnIdle { assertEquals(1, actions) }
        // This fixture Activity has no navigation handler; dispose the selection container itself.
        compose.runOnIdle { selecting.value = false }
        compose.onNodeWithTag("message").performTouchInput { longClick(center) }
        compose.runOnIdle { assertEquals(2, actions) }
        assertFalse(device.hasObject(copy))
    }

    @Test fun tapOpensLinkButLongPressCopiesSelectedText() {
        var opened: String? = null
        compose.setContent { TouchTheme { Column(Modifier.padding(top = 64.dp)) {
            MessageText("https://example.com", { opened = it }, Modifier.testTag("link"))
            MessageText("copyme", { opened = it }, Modifier.testTag("plain"))
        } } }
        compose.onNodeWithTag("link").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("https://example.com", opened); opened = null }
        compose.onNodeWithTag("link").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, 150f), durationMillis = 150)
        }
        compose.runOnIdle { assertNull(opened) }
        compose.onNodeWithTag("plain").performTouchInput { longClick(center) }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val copy = By.text(java.util.regex.Pattern.compile("Copy|复制"))
        assertTrue("bounds=" + compose.onNodeWithTag("plain").fetchSemanticsNode().boundsInRoot + "; texts=" + device.findObjects(By.text(java.util.regex.Pattern.compile(".+"))).map { it.text }, device.wait(Until.hasObject(copy), 5000))
        device.findObject(copy).click()
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertEquals("copyme", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            assertNull(opened)
            clipboard.clearPrimaryClip()
        }
        compose.onNodeWithTag("link").performTouchInput { longClick(center) }
        val copyVisible = device.wait(Until.hasObject(copy), 5000)
        assertTrue("opened=$opened; texts=" + device.findObjects(By.text(java.util.regex.Pattern.compile(".+"))).map { it.text }, copyVisible)
        device.findObject(copy).click()
        compose.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val selected = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(selected.isNotBlank() && "https://example.com".contains(selected))
            assertNull(opened)
            clipboard.clearPrimaryClip()
        }
    }
}
