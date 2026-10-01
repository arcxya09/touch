package com.arcxya09.touch.timer

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arcxya09.touch.MainActivity
import com.arcxya09.touch.R

data class TimerState(val focusMinutes: Int, val restMinutes: Int, val resting: Boolean,
    val running: Boolean, val remainingMs: Long, val complete: Boolean) {
    val durationMs get() = (if (resting) restMinutes else focusMinutes) * 60000L
}

class Pomodoro(private val context: Context) {
    private val prefs = context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE)
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private fun pending() = PendingIntent.getBroadcast(context, 25,
        Intent(context, TimerReceiver::class.java).setAction("com.arcxya09.touch.TIMER_END"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private val engine = TimerEngine(object : TimerStorage {
        override fun read(): TimerRecord {
            val focus = prefs.getInt("focus", 25).coerceIn(1, 180)
            val rest = prefs.getInt("rest", 5).coerceIn(1, 60)
            val resting = prefs.getBoolean("resting", false)
            return TimerRecord(focus, rest, resting, prefs.getBoolean("running", false),
                prefs.getLong("remaining", (if (resting) rest else focus) * 60000L), prefs.getBoolean("complete", false),
                prefs.getLong("elapsedEnd", 0), prefs.getLong("wallEnd", 0), prefs.getInt("boot", -2))
        }
        override fun write(record: TimerRecord) {
            check(prefs.edit().putInt("focus", record.focusMinutes).putInt("rest", record.restMinutes)
                .putBoolean("resting", record.resting).putBoolean("running", record.running)
                .putLong("remaining", record.remainingMs).putBoolean("complete", record.complete)
                .putLong("elapsedEnd", record.elapsedEndMs).putLong("wallEnd", record.wallEndMs)
                .putInt("boot", record.bootCount).commit()) { "计时状态未能保存，请重试" }
        }
    }, object : TimerClock {
        override fun elapsedMs() = SystemClock.elapsedRealtime()
        override fun wallMs() = System.currentTimeMillis()
        override fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }, object : TimerAlarm {
        override fun cancel() { alarm.cancel(pending()) }
        override fun schedule(elapsedDeadlineMs: Long) {
            if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                try {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDeadlineMs, pending())
                    return
                } catch (_: SecurityException) {
                    // Exact-alarm permission can be revoked between the check and scheduling.
                }
            }
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDeadlineMs, pending())
        }
    })
    fun state() = engine.state()
    fun configure(focus: Int, rest: Int) { engine.configure(focus, rest); clearNotification() }
    fun choose(resting: Boolean) { engine.choose(resting); clearNotification() }
    fun start() = engine.start()
    fun pause() = engine.pause()
    fun reset() { engine.reset(); clearNotification() }
    fun schedule() = engine.schedule()
    private fun clearNotification() { context.getSystemService(NotificationManager::class.java).cancel(25) }
    fun finish(notify: Boolean) {
        val state = engine.state()
        if (!engine.finish() || !notify) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("pomodoro", "番茄钟提醒", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 25, Intent(context, MainActivity::class.java).putExtra("timer", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(25, NotificationCompat.Builder(context, "pomodoro").setSmallIcon(R.drawable.ic_touch)
            .setContentTitle("Touch番茄钟").setContentText(if (state.resting) "休息结束，开始下一段专注吧" else "专注完成，休息一下吧")
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_ALARM).build())
    }
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timer = Pomodoro(context)
        if (intent.action == "com.arcxya09.touch.TIMER_END") timer.finish(true) else timer.schedule()
    }
}
