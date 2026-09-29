package com.arcxya09.touch
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.arcxya09.touch.notifications.AlertService
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
class ConnectionReviewTest {
 @get:Rule val compose = createEmptyComposeRule()
 private val app get() = ApplicationProvider.getApplicationContext<TouchApp>()
 private suspend fun prepare() {
  val args = InstrumentationRegistry.getArguments()
  assumeTrue(args.getString("touchTestUser")?.startsWith("verify_local_") == true)
  app.repository.initialize(); app.repository.logout(false)
  app.repository.login(args.getString("touchTestUser")!!, args.getString("touchTestPassword")!!)
  app.repository.sync()
  app.secureStore.write("privacy", JSONObject().put("enabled", false).toString())
  File(app.filesDir,"privacy.enabled").delete()
 }
 private fun syncJob(vm: AppViewModel) = AppViewModel::class.java.getDeclaredField("syncJob").apply { isAccessible = true }.get(vm) as Job?
 @Test fun normalTimerReturnRestartsSync() = runBlocking<Unit> {
  prepare()
  ActivityScenario.launch(MainActivity::class.java).use { scenario ->
   lateinit var vm: AppViewModel
   scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
   compose.waitUntil(15000) { vm.connected }
   compose.onNodeWithContentDescription("返回番茄钟").performClick()
   compose.onNodeWithText("返回", useUnmergedTree = true).performClick()
   compose.waitUntil(5000) { vm.screen == "home" && vm.mayShowChat }
   app.repository.sync() // Same server, same session, network succeeds.
   compose.waitUntil(15000) { vm.connected }
   assertTrue(vm.connected)
   assertTrue(syncJob(vm)?.isActive == true)
  }
 }
 @Test fun failedServiceStartFallsBackToForegroundSync() = runBlocking<Unit> {
  prepare()
  assertTrue(app.alerts.allowed())
  app.alertSettings.enable(app.repository.api.user!!.id)
  val component = ComponentName(app, AlertService::class.java)
  app.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
  try {
   ActivityScenario.launch(MainActivity::class.java).use { scenario ->
    lateinit var vm: AppViewModel
    scenario.onActivity { vm = ViewModelProvider(it)[AppViewModel::class.java] }
    compose.waitUntil(15000) { vm.initialized && vm.mayShowChat }
    compose.waitUntil(15000) { vm.connected }
    assertFalse(AlertService.running); assertTrue(vm.connected)
    assertTrue(syncJob(vm)?.isActive == true)
    app.repository.sync()
    compose.waitUntil(15000) { vm.connected }
   }
  } finally {
   app.alertSettings.disable()
   app.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
  }
 }
}
