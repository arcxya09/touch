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
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun pending() = PendingIntent.getBroadcast(context, 25,
        Intent(context, TimerReceiver::class.java).setAction("com.arcxya09.touch.TIMER_END"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun state(): TimerState {
        val focus = prefs.getInt("focus", 25)
        val rest = prefs.getInt("rest", 5)
        val resting = prefs.getBoolean("resting", false)
        val running = prefs.getBoolean("running", false)
        val remaining = if (running) {
            if (prefs.getInt("boot", -2) == boot()) prefs.getLong("elapsedEnd", 0) - SystemClock.elapsedRealtime()
            else prefs.getLong("wallEnd", 0) - System.currentTimeMillis()
        } else prefs.getLong("remaining", (if (resting) rest else focus) * 60000L)
        return TimerState(focus, rest, resting, running, remaining.coerceAtLeast(0), prefs.getBoolean("complete", false))
    }
    fun configure(focus: Int, rest: Int) {
        require(focus in 1..180 && rest in 1..60)
        prefs.edit().putInt("focus", focus).putInt("rest", rest).commit()
        reset()
    }
    fun choose(resting: Boolean) { prefs.edit().putBoolean("resting", resting).commit(); reset() }
    fun start() {
        val state = state()
        if (state.running) return
        val remaining = if (state.remainingMs == 0L) state.durationMs else state.remainingMs
        prefs.edit().putBoolean("running", true).putBoolean("complete", false)
            .putLong("elapsedEnd", SystemClock.elapsedRealtime() + remaining)
            .putLong("wallEnd", System.currentTimeMillis() + remaining).putInt("boot", boot()).commit()
        schedule()
    }
    fun pause() {
        val remaining = state().remainingMs
        prefs.edit().putBoolean("running", false).putLong("remaining", remaining).commit()
        alarm.cancel(pending())
    }
    fun reset() {
        prefs.edit().putBoolean("running", false).putBoolean("complete", false).putLong("remaining", state().durationMs).commit()
        alarm.cancel(pending())
        context.getSystemService(NotificationManager::class.java).cancel(25)
    }
    fun schedule() {
        val state = state()
        if (!state.running) return
        if (state.remainingMs <= 0) { finish(false); return }
        val end = SystemClock.elapsedRealtime() + state.remainingMs
        prefs.edit().putInt("boot", boot()).putLong("elapsedEnd", end)
            .putLong("wallEnd", System.currentTimeMillis() + state.remainingMs).commit()
        if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, pending())
        } else alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, pending())
    }
    fun finish(notify: Boolean) {
        if (!prefs.getBoolean("running", false)) return
        val state = state()
        // Old or duplicate alarm delivery must never finish a newly restarted timer early.
        if (state.remainingMs > 1000) { schedule(); return }
        prefs.edit().putBoolean("running", false).putBoolean("complete", true).putLong("remaining", 0).commit()
        alarm.cancel(pending())
        if (!notify) return
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
