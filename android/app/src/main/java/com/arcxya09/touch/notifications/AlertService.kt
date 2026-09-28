package com.arcxya09.touch.notifications

import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.net.*
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arcxya09.touch.TouchApp
import com.arcxya09.touch.data.ApiException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One socket owner even while no activity exists. The tile only controls this service. */
class AlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val app get() = application as TouchApp
    private var loop: Job? = null
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wake.trySend(Unit) }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { app.alerts.concealOnLock() }
    }
    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(this, AlertNotifications.RUNNING_ID, app.alerts.running(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        running = true
        ReminderTileService.refresh(this)
        ContextCompat.registerReceiver(this, screen, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        app.repository.onIncoming = { messages -> scope.launch { runCatching { app.alerts.incoming(messages) } } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loop?.isActive != true) loop = scope.launch {
            try {
                app.repository.initialize()
                var retry = 2000L
                while (isActive) {
                    val options = withContext(Dispatchers.IO) { app.alertSettings.read() }
                    if (!options.canRun(app.repository.api.user?.id) || app.repository.api.user?.mustChange == true || !app.alerts.allowed()) break
                    try {
                        app.repository.sync()
                        connected = true; retry = 2000L
                        app.repository.connect { wake.trySend(Unit) }
                        withTimeoutOrNull(30000) { wake.receive() }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        connected = false
                        if (e is ApiException && e.status == 401) {
                            withContext(NonCancellable) { app.repository.logout(false) }
                            break
                        }
                        app.repository.disconnect()
                        withTimeoutOrNull(retry) { wake.receive() }
                        retry = (retry * 2).coerceAtMost(60000)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Unreadable credentials/configuration must leave background access stopped.
            } finally { stopSelf() }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        running = false; connected = false
        app.repository.onIncoming = null
        scope.cancel()
        app.repository.disconnect()
        runCatching { unregisterReceiver(screen) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        app.alerts.clearMessages()
        stopForeground(STOP_FOREGROUND_REMOVE)
        ReminderTileService.refresh(this)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        @Volatile var running = false; private set
        @Volatile var connected = false; private set
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java)) != null
        }.getOrDefault(false)
        fun stop(context: Context) {
            (context.applicationContext as TouchApp).alerts.clearMessages()
            context.stopService(Intent(context, AlertService::class.java))
            ReminderTileService.refresh(context)
        }
    }
}

class AlertStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val app = context.applicationContext as TouchApp
                withContext(Dispatchers.IO) { app.alertSettings.pause(true) }
                AlertService.stop(app)
            } catch (_: Exception) { AlertService.stop(context) }
            finally { pending.finish() }
        }
    }
}

class AlertBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as TouchApp
                val options = app.alertSettings.read()
                if (options.enabled && !options.paused && app.alerts.allowed()) AlertService.start(app)
            } catch (_: Exception) { /* Remain off on unreadable configuration. */ }
            finally { pending.finish() }
        }
    }
}
