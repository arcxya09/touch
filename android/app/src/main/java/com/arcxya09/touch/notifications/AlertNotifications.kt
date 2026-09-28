package com.arcxya09.touch.notifications

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.R
import com.arcxya09.touch.TouchApp
import com.arcxya09.touch.data.ChatMessage

class AlertNotifications(private val app: TouchApp) {
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    @Volatile var chatVisible = false
    fun allowed() = NotificationManagerCompat.from(app).areNotificationsEnabled()
    fun channels() {
        manager.createNotificationChannel(NotificationChannel(RUNNING_CHANNEL, "后台运行", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); enableVibration(false); setSound(null, null)
        })
        manager.createNotificationChannel(NotificationChannel(DISCREET_CHANNEL, "休息提示", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); enableVibration(false); setSound(null, null)
        })
        manager.createNotificationChannel(NotificationChannel(CONTENT_CHANNEL, "内容提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setShowBadge(false)
        })
    }
    private fun open(timer: Boolean): PendingIntent = PendingIntent.getActivity(app, if (timer) 4103 else 4104,
        Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).putExtra("timer", timer).putExtra("message_alert", !timer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun running(): Notification {
        channels()
        val stop = PendingIntent.getBroadcast(app, 4101, Intent(app, AlertStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(app, RUNNING_CHANNEL).setSmallIcon(R.drawable.ic_touch)
            .setContentTitle("Touch番茄钟").setContentText("后台提醒已开启")
            .setGroup(GROUP).setGroupSummary(true).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(open(true)).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setShowWhen(false).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "暂停提醒", stop).build()
    }
    private fun discreet() = NotificationCompat.Builder(app, DISCREET_CHANNEL).setSmallIcon(R.drawable.ic_touch)
        .setContentTitle("Touch番茄钟").setContentText("已经专注一段时间了，记得休息一下。")
        .setGroup(GROUP).setContentIntent(open(true)).setAutoCancel(true).setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setNumber(0).build()
    suspend fun incoming(messages: List<ChatMessage>) {
        val options = app.alertSettings.read()
        val owner = app.repository.api.user?.id
        if (!options.canRun(owner) || !AlertService.running || chatVisible || !allowed()) return
        val latest = messages.lastOrNull { it.senderId != owner } ?: return
        // Re-check local retention and personal deletion before publishing delayed events.
        if (app.repository.messages(latest.conversationId).none { it.id == latest.id }) return
        val peer = app.repository.conversations().firstOrNull { it.id == latest.conversationId }?.peer
        val locked = app.getSystemService(KeyguardManager::class.java).isDeviceLocked ||
            !app.getSystemService(android.os.PowerManager::class.java).isInteractive
        val notification = if (options.mode == AlertMode.DISCREET || locked) discreet() else {
            val body = when (latest.kind) { "text" -> latest.text.take(160); "image" -> "[图片]"; else -> "[文件]" }
            NotificationCompat.Builder(app, CONTENT_CHANNEL).setSmallIcon(R.drawable.ic_touch)
                .setContentTitle(peer?.name ?: "新消息").setContentText(body)
                .setGroup(GROUP).setContentIntent(open(false)).setAutoCancel(true).setNumber(0)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET).build()
        }
        // A mode switch/disable may have happened during database reads.
        if (!AlertService.running || app.alertSettings.read() != options || chatVisible || !options.canRun(app.repository.api.user?.id)) return
        runCatching { manager.notify(MESSAGE_ID, notification) }
    }
    fun concealOnLock() {
        if (manager.activeNotifications.any { it.id == MESSAGE_ID } && allowed()) {
            manager.cancel(MESSAGE_ID)
            runCatching { manager.notify(MESSAGE_ID, discreet()) }
        }
    }
    fun clearMessages() = manager.cancel(MESSAGE_ID)
    // Android may synthesize a group summary that launches MAIN without our extras.
    fun hasDiscreetMessage() = manager.activeNotifications.any {
        it.id == MESSAGE_ID && it.notification.channelId == DISCREET_CHANNEL
    }
    companion object {
        const val GROUP = "touch_background"
        const val RUNNING_ID = 4101
        const val MESSAGE_ID = 4102
        const val RUNNING_CHANNEL = "background_reminders"
        const val DISCREET_CHANNEL = "rest_suggestions"
        const val CONTENT_CHANNEL = "content_alerts"
    }
}
