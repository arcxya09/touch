package com.arcxya09.touch.notifications

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.TouchApp
import kotlinx.coroutines.*

class ReminderTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as TouchApp
    override fun onStartListening() { super.onStartListening(); update() }
    private fun update() { scope.launch {
        val options = withContext(Dispatchers.IO) { runCatching { app.alertSettings.read() }.getOrNull() }
        qsTile?.apply {
            label = "Touch番茄钟"
            state = if (AlertService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (AlertService.running) "后台提醒已开启" else if (options?.enabled == true) "后台提醒已暂停" else "未开启提醒"
            updateTile()
        }
    } }
    override fun onClick() {
        super.onClick()
        unlockAndRun { scope.launch {
            try {
                val options = withContext(Dispatchers.IO) { app.alertSettings.read() }
                if (!options.enabled || !app.alerts.allowed()) { openSettings(); return@launch }
                if (AlertService.running) {
                    withContext(Dispatchers.IO) { app.alertSettings.pause(true) }
                    AlertService.stop(app)
                } else {
                    withContext(Dispatchers.IO) { app.alertSettings.pause(false) }
                    if (!AlertService.start(app)) openSettings()
                }
                update()
            } catch (_: Exception) { openSettings() }
        } }
    }
    // PendingIntent overload was introduced in API 34; the legacy call is only used below it.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION") private fun openSettings() {
        val intent = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).putExtra("notification_settings", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 4105, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(intent)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        fun refresh(context: Context) = requestListeningState(context, ComponentName(context, ReminderTileService::class.java))
    }
}
