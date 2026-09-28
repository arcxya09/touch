package com.arcxya09.touch

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.arcxya09.touch.notifications.AlertService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Run with POST_NOTIFICATIONS revoked on the isolated test device. */
class AlertPermissionTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun deniedSystemPermissionKeepsAlertsOff() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("touchTestUser")?.startsWith("verify_local_") == true)
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        assertFalse(app.alerts.allowed())
        app.repository.initialize(); app.repository.logout(false)
        app.repository.login(args.getString("touchTestUser")!!, args.getString("touchTestPassword")!!)
        app.secureStore.write("privacy", JSONObject().put("enabled", false).toString())
        File(app.filesDir, "privacy.enabled").delete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: AppViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
            compose.waitUntil(15000) { vm.mayShowChat }
            scenario.onActivity { vm.enableAlerts(true); vm.screen = "notifications" }
            compose.waitUntil(10000) { !vm.busy && vm.error != null }
            assertFalse(vm.alertOptions.enabled); assertFalse(AlertService.running)
            scenario.onActivity { vm.error = null }
            compose.onNodeWithContentDescription("消息通知开关").performClick()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_deny_button")), 10000)
            assertNotNull(deny)
            device.waitForIdle(3000)
            // PermissionController publishes nodes before its opening transition accepts touches.
            repeat(3) {
                Thread.sleep(600)
                device.findObject(By.res("com.android.permissioncontroller", "permission_deny_button"))?.click()
            }
            compose.waitUntil(10000) { vm.error != null }
            assertFalse(app.alerts.allowed()); assertFalse(vm.alertOptions.enabled); assertFalse(AlertService.running)
        }
    }
}
