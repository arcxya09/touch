package com.arcxya09.touch

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arcxya09.touch.data.*
import com.arcxya09.touch.notifications.*
import com.arcxya09.touch.data.Retention
import com.arcxya09.touch.security.Pattern
import com.arcxya09.touch.timer.Pomodoro
import com.arcxya09.touch.timer.TimerState
import com.arcxya09.touch.update.UpdateManifest
import com.arcxya09.touch.update.Updater
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TouchApp
    val repository = app.repository
    val pomodoro = Pomodoro(app)
    private val marker = File(app.filesDir, "privacy.enabled")
    private val attempts = app.getSharedPreferences("gesture_attempts", Application.MODE_PRIVATE)
    private var pattern = ""
    private var syncJob: Job? = null
    private var resumeJob: Job? = null
    private var work: Job? = null
    private var updateJob: Job? = null
    private var foreground = false
    var alertOptions by mutableStateOf(AlertOptions()); private set
    var backgroundAlerts by mutableStateOf(false); private set
    var pendingNotificationSettings = false
    var pendingNotificationEnable = false
    fun renderedChat() {
        app.alerts.chatVisible = foreground && mayShowChat && user != null && user?.mustChange == false && screen != "timer"
        if (app.alerts.chatVisible) app.alerts.clearMessages()
    }
    var safety by mutableStateOf(com.arcxya09.touch.security.SafetyOptions()); private set
    fun saveSafety(value: com.arcxya09.touch.security.SafetyOptions) = action {
        withContext(Dispatchers.IO) { value.save(app.vault) }; safety = value
    }
    var initialized by mutableStateOf(false); private set
    var privacy by mutableStateOf(marker.exists()); private set
    var locked by mutableStateOf(true); private set
    var user by mutableStateOf<Person?>(null); private set
    var screen by mutableStateOf("home")
    var conversations by mutableStateOf(emptyList<Conversation>()); private set
    var contacts by mutableStateOf(emptyList<ContactItem>()); private set
    var messages by mutableStateOf(emptyList<ChatMessage>()); private set
    var conversationId by mutableStateOf<String?>(null); private set
    var hasMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var transfer by mutableStateOf<Float?>(null); private set
    var connectionStatus by mutableStateOf(ConnectionStatus.CONNECTING); private set
    val connected get() = connectionStatus == ConnectionStatus.LIVE
    private fun refreshConnectionStatus() {
        connectionStatus = if (foreground && mayShowChat) repository.connection.status() else ConnectionStatus.CONNECTING
    }
    var foundPerson by mutableStateOf<Person?>(null); private set
    var preview by mutableStateOf<Pair<FileItem, EncryptedAttachment>?>(null); private set
    var retentionEnabled by mutableStateOf(false); private set
    var pendingAvatar by mutableStateOf<Uri?>(null)
    var retentionSeconds by mutableStateOf(Retention.DEFAULT_SECONDS); private set
    var storageError by mutableStateOf(false); private set
    private var cacheReady by mutableStateOf(false)
    var pendingSelection by mutableStateOf<Pair<Uri, String>?>(null)
    var timer by mutableStateOf(pomodoro.state()); private set
    var update by mutableStateOf<UpdateManifest?>(null)
    var updateApk by mutableStateOf<File?>(null); private set
    var updateProgress by mutableStateOf<Float?>(null); private set
    var showUpdate by mutableStateOf(false)
    var updater: Updater? = null
    private var privacyChoiceMade = false
    var needsPrivacySetup by mutableStateOf(false); private set
    val mayShowChat get() = mayShowSession && !needsPrivacySetup
    val mayShowSession get() = initialized && cacheReady && !storageError && (!privacy || !locked)

    init {
        viewModelScope.launch { repository.connection.state.collect { refreshConnectionStatus() } }
        // A timer that expired while this process was absent must not ring on reopening.
        pomodoro.schedule()
        viewModelScope.launch {
            runCatching {
                val config = app.secureStore.read("privacy")?.let(::JSONObject)
                privacy = marker.exists() || config?.optBoolean("enabled") == true
                pattern = config?.optString("pattern").orEmpty()
                privacyChoiceMade = privacy || (config != null && !config.optBoolean("pending"))
                needsPrivacySetup = config?.optBoolean("pending") == true
            }.onFailure { privacy = true; privacyChoiceMade = true }
            try {
                safety = withContext(Dispatchers.IO) { com.arcxya09.touch.security.SafetyOptions.read(app.vault) }
                repository.initialize()
                alertOptions = withContext(Dispatchers.IO) { app.alertSettings.read() }
                retentionEnabled = withContext(Dispatchers.IO) { repository.retention.enabled }
                retentionSeconds = withContext(Dispatchers.IO) { repository.retention.seconds }
                user = repository.api.user
                // Preserve the chosen normal mode for already signed-in upgrades.
                if (user != null && !privacyChoiceMade && !needsPrivacySetup) {
                    app.secureStore.write("privacy", JSONObject().put("enabled", false).toString())
                    privacyChoiceMade = true
                }
                reloadLocal()
                locked = privacy; cacheReady = true
                initialized = true
                if (foreground && mayShowChat) startForegroundWork()
            } catch (_: Exception) { storageError = true }
        }
        viewModelScope.launch {
            while (isActive) {
                val state = pomodoro.state()
                if (state.running && state.remainingMs == 0L) pomodoro.finish(true)
                timer = pomodoro.state()
                refreshConnectionStatus()
                if (initialized && foreground && !storageError) {
                    try {
                        alertOptions = withContext(Dispatchers.IO) { app.alertSettings.read() }
                        backgroundAlerts = AlertService.running
                        if (repository.purge() || AlertService.running || (user != null && repository.api.user == null)) reloadLocal()
                        if (foreground && mayShowChat && !busy && pendingNotificationEnable) {
                            pendingNotificationEnable = false; enableAlerts(true)
                        }
                        if (preview?.second?.let { withContext(Dispatchers.IO) { !it.valid() } } == true) {
                            preview = null; if (screen == "preview") screen = "chat"
                        }
                    } catch (_: Exception) { storageError = true; cacheReady = false; preview = null; messages = emptyList(); conversations = emptyList() }
                }
                delay(1000)
            }
        }
    }

    fun resume() {
        foreground = true
        if (!initialized || storageError) return
        cacheReady = false
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            try { repository.purge(); reloadLocal(); cacheReady = true; if (foreground && mayShowChat) startForegroundWork() }
            catch (e: Exception) { if (e is CancellationException) throw e; storageError = true }
        }
    }
    fun background() {
        foreground = false
        resumeJob?.cancel()
        app.alerts.chatVisible = false
        cacheReady = false
        if (privacy) locked = true
        showUpdate = false
        syncJob?.cancel(); work?.cancel(); updateJob?.cancel()
        if (!AlertService.running) repository.stop()
        busy = false; transfer = null; updateProgress = null; connectionStatus = ConnectionStatus.CONNECTING
        preview = null
        if (screen == "preview") screen = if (conversationId != null) "chat" else "home"
    }
    fun hide() {
        background()
        foreground = true
        cacheReady = true
        locked = true
        if (!privacy) screen = "timer"
    }
    fun returnFromTimer() {
        if (privacy || !mayShowChat) return
        screen = "home"; startForegroundWork()
    }
    fun unlock(points: List<Int>) {
        if (!privacy || !initialized || pattern.isEmpty()) return
        if (System.currentTimeMillis() < attempts.getLong("until", 0)) return
        if (MessageDigest.isEqual(Pattern.encode(points).toByteArray(), pattern.toByteArray())) {
            attempts.edit().clear().commit()
            locked = false; error = null
            if (screen == "timer") screen = "home"
            startForegroundWork()
        } else {
            val count = attempts.getInt("failures", 0) + 1
            attempts.edit().putInt("failures", if (count >= 5) 0 else count)
                .putLong("until", if (count >= 5) System.currentTimeMillis() + 30000 else 0).commit()
        }
    }
    fun setPrivacy(newPattern: String) = action(allowSetup = true) {
        withContext(Dispatchers.IO) { marker.writeText("1") }
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", newPattern).toString())
        privacyChoiceMade = true; needsPrivacySetup = false
        pattern = newPattern
        privacy = true; locked = true; screen = "home"; showUpdate = false
        syncJob?.cancel(); app.alerts.chatVisible = false; if (!AlertService.running) repository.stop()
    }
    fun skipPrivacySetup() = action(allowSetup = true) {
        if (!needsPrivacySetup || user == null || user?.mustChange == true) return@action
        app.secureStore.write("privacy", JSONObject().put("enabled", false).toString())
        privacyChoiceMade = true; needsPrivacySetup = false
        screen = "home"; startForegroundWork()
    }
    fun disablePrivacy(password: String) = action {
        repository.verifyPassword(password)
        app.secureStore.write("privacy", JSONObject().put("enabled", false).toString())
        withContext(Dispatchers.IO) { check(!marker.exists() || marker.delete()) { "无法保存隐私设置" } }
        privacy = false; locked = false; pattern = ""
    }
    fun verifyPrivacyPassword(password: String, success: () -> Unit) = action { repository.verifyPassword(password); success() }
    fun login(name: String, password: String) = action(allowSetup = true) {
        if (!privacyChoiceMade) {
            // Persist before login: a process death must not bypass the choice.
            app.secureStore.write("privacy", JSONObject().put("pending", true).toString())
            needsPrivacySetup = true
        }
        repository.login(name, password); user = repository.api.user
        screen = "home"; error = null; startForegroundWork()
    }
    fun password(old: String, new: String) = action(allowSetup = true) {
        repository.updatePassword(old, new); user = repository.api.user; screen = "home"; startForegroundWork()
    }
    fun logout() = action(allowSetup = true) {
        syncJob?.cancel(); repository.logout(); user = null
        conversations = emptyList(); contacts = emptyList(); messages = emptyList(); conversationId = null; pendingAvatar = null; pendingSelection = null; preview = null
        screen = "home"; if (privacy) locked = true
    }
    private fun startForegroundWork() {
        if (!foreground || !mayShowChat) return
        if (pendingNotificationSettings && user != null && user?.mustChange == false) {
            pendingNotificationSettings = false; screen = "notifications"
        }
        autoUpdate()
        if (user == null || user?.mustChange == true || syncJob?.isActive == true) return
        // Never inherit yesterday's success when returning from the background.
        repository.recheck()
        AlertService.wake()
        syncJob = viewModelScope.launch {
            val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            var lastServiceAttempt = -60000L
            var retry = 2000L
            try {
                while (isActive && foreground && mayShowChat) {
                    try {
                        val options = withContext(Dispatchers.IO) { app.alertSettings.read() }
                        alertOptions = options
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (options.canRun(user?.id) && app.alerts.allowed() && !AlertService.running && now - lastServiceAttempt >= 60000) {
                            lastServiceAttempt = now; AlertService.start(app)
                        }
                        if (AlertService.running) {
                            reloadLocal(); refreshConnectionStatus(); delay(1000); continue
                        }
                        // Connect before sync; any event arriving during sync stays in this one-slot queue.
                        repository.connect { wake.trySend(Unit) }
                        repository.sync(); reloadLocal(); refreshConnectionStatus()
                        retry = if (repository.connection.state.value.socketOpen) 2000L else (retry * 2).coerceAtMost(15000)
                        withTimeoutOrNull(15000) { wake.receive() }
                        if (!repository.connection.state.value.socketOpen) delay(retry)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        repository.disconnect(keepListener = true); refreshConnectionStatus(); handleError(e, false)
                        withTimeoutOrNull(retry) { wake.receive() }
                        retry = (retry * 2).coerceAtMost(15000)
                    }
                }
            } finally { wake.close() }
        }
    }

    private suspend fun reloadLocal() {
        if (user != null && repository.api.user == null) {
            app.alerts.chatVisible = false
            conversations = emptyList(); contacts = emptyList(); messages = emptyList(); preview = null
            conversationId = null; pendingSelection = null; pendingAvatar = null; screen = "home"
            if (privacy) locked = true
        }
        user = repository.api.user
        conversations = repository.conversations(); contacts = repository.contacts()
        conversationId?.let { messages = repository.messages(it) }
    }
    private var reportedRead = 0L
    fun markVisibleRead(seq: Long) {
        val id = conversationId ?: return
        if (!foreground || !mayShowChat || screen != "chat" || seq <= reportedRead) return
        reportedRead = seq
        viewModelScope.launch {
            try { repository.read(id, seq) }
            catch (_: Exception) { if (conversationId == id) reportedRead = 0 }
        }
    }
    fun openConversation(id: String) {
        conversationId = id; reportedRead = 0; screen = "chat"
        action {
            messages = repository.messages(id)
            hasMore = repository.history(id)
            reloadLocal()
        }
    }
    fun older() = action {
        val id = conversationId ?: return@action
        val before = messages.filterNot { it.pending }.minOfOrNull { it.seq }
        hasMore = repository.history(id, before); reloadLocal()
    }
    fun send(text: String, clear: () -> Unit) = action {
        val id = conversationId ?: return@action
        try { repository.send(id, text); repository.sync() } finally { clear(); reloadLocal() }
    }
    fun retry(id: String) = action { try { repository.retry(id); repository.sync() } finally { reloadLocal() } }
    fun discard(id: String) = action { repository.discard(id); reloadLocal() }
    fun deleteConversation(id: String) = action {
        repository.deleteConversation(id)
        if (conversationId == id) {
            conversationId = null; messages = emptyList(); preview = null; pendingSelection = null; hasMore = false
        }
        screen = "home"; reloadLocal()
    }
    fun search(name: String) = action { foundPerson = null; foundPerson = repository.search(name) }
    fun request() = action { foundPerson?.let { repository.request(it) }; foundPerson = null; reloadLocal() }
    fun contactAction(person: Person, operation: String) = action { repository.contactAction(person.id, operation); reloadLocal() }
    fun sendSelection() = action {
        val selection = pendingSelection ?: return@action
        val id = conversationId ?: return@action
        transfer = 0f
        try {
            val file = repository.upload(selection.first, selection.second) { value -> viewModelScope.launch { transfer = value } }
            pendingSelection = null
            repository.send(id, attachment = file); repository.sync()
        } finally { transfer = null; reloadLocal() }
    }
    fun openFile(message: ChatMessage) = action {
        transfer = 0f
        try {
            val path = repository.download(message) { value -> viewModelScope.launch { transfer = value } }
            if (mayShowChat && foreground) { preview = path.item to path; screen = "preview" }
        } finally { transfer = null }
    }
    fun cancelTransfer() { work?.cancel(); repository.api.closeConnections(); transfer = null; busy = false }
    fun timerStart() { pomodoro.start(); timer = pomodoro.state() }
    fun timerPause() { pomodoro.pause(); timer = pomodoro.state() }
    fun timerReset() { pomodoro.reset(); timer = pomodoro.state() }
    fun timerChoose(rest: Boolean) { pomodoro.choose(rest); timer = pomodoro.state() }
    fun timerConfigure(focus: Int, rest: Int) { pomodoro.configure(focus, rest); timer = pomodoro.state() }
    fun enableRetention(enabled: Boolean) = action {
        repository.enableRetention(enabled)
        retentionEnabled = repository.retention.enabled
        retentionSeconds = repository.retention.seconds
        preview = null
        reloadLocal()
    }
    fun saveProfile(name: String, bio: String) = action {
        repository.updateProfile(name, bio); user = repository.api.user
        repository.sync(); reloadLocal(); if (mayShowChat) error = "个人资料已保存"
    }
    fun uploadAvatar() = action {
        val uri = pendingAvatar ?: return@action
        repository.uploadAvatar(uri); pendingAvatar = null; user = repository.api.user
        repository.sync(); reloadLocal()
    }
    fun removeAvatar() = action { repository.removeAvatar(); user = repository.api.user; repository.sync(); reloadLocal() }
    fun setReadReceipts(enabled: Boolean) = action {
        repository.setReadReceipts(enabled); user = repository.api.user
        repository.sync(); reloadLocal()
    }
    fun setRetention(hours: Long) = action {
        require(hours in 1..8760) { "请输入 1 至 8760 小时" }
        repository.configureRetention(hours * 3600)
        retentionSeconds = hours * 3600
        preview = null
        reloadLocal()
    }

    fun enableAlerts(enabled: Boolean) = action {
        if (enabled) {
            val owner = user?.takeUnless { it.mustChange }?.id ?: return@action
            if (!app.alerts.allowed()) { error = "请先在系统设置中允许通知，再开启消息通知"; return@action }
            // Catch up without alerting about historical messages on first opt-in.
            repository.sync()
            alertOptions = withContext(Dispatchers.IO) { app.alertSettings.enable(owner) }
            syncJob?.cancel(); repository.disconnect()
            if (!AlertService.start(app)) error = "系统暂不允许启动后台提醒，请重新打开应用后重试"
        } else {
            alertOptions = withContext(Dispatchers.IO) { app.alertSettings.disable() }
            AlertService.stop(app)
            syncJob?.cancel()
        }
        syncJob = null; startForegroundWork()
    }
    fun alertMode(mode: AlertMode) = action {
        alertOptions = withContext(Dispatchers.IO) { app.alertSettings.mode(mode) }
        app.alerts.clearMessages()
    }
    fun pauseAlerts(paused: Boolean) = action {
        alertOptions = withContext(Dispatchers.IO) { app.alertSettings.pause(paused) }
        if (paused) AlertService.stop(app)
        else if (!app.alerts.allowed()) error = "系统通知权限未开启，请先允许通知"
        else if (!AlertService.start(app)) error = "后台提醒启动失败，请重新打开应用后重试"
        syncJob?.cancel(); syncJob = null; startForegroundWork()
    }

    fun checkUpdate(manual: Boolean = true) {
        if (!foreground || !mayShowChat || updateJob?.isActive == true) return
        val service = updater ?: return
        if (!manual && !service.due()) return
        updateJob = viewModelScope.launch {
            try {
                val result = service.check()
                if (foreground && mayShowChat) {
                    if (update?.versionCode != result?.versionCode) updateApk = null
                    update = result; showUpdate = result != null
                    if (manual && result == null) error = "当前已是最新版本"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (manual && mayShowChat) error = e.message ?: "更新检查失败"
            }
        }
    }
    private fun autoUpdate() = checkUpdate(false)
    fun downloadUpdate() {
        val manifest = update ?: return
        if (!foreground || !mayShowChat || updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            updateProgress = 0f
            try {
                updateApk = updater!!.download(manifest) { value -> viewModelScope.launch { updateProgress = value } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (mayShowChat) error = e.message ?: "下载失败"
            } finally { updateProgress = null }
        }
    }
    fun cancelUpdate() { updateJob?.cancel(); updater?.cancel(); updateProgress = null }
    fun action(allowSetup: Boolean = false, block: suspend () -> Unit) {
        if (busy || !mayShowSession || (needsPrivacySetup && !allowSetup)) return
        work = viewModelScope.launch {
            busy = true; error = null
            try { block() } catch (e: Exception) {
                if (e is CancellationException) throw e
                handleError(e, true)
            } finally { busy = false }
        }
    }
    private suspend fun handleError(e: Exception, visible: Boolean) {
        if (e is ApiException && e.status == 401) {
            withContext(NonCancellable) {
                syncJob?.cancel(); repository.logout(false); user = null
                conversations = emptyList(); contacts = emptyList(); messages = emptyList(); preview = null; pendingAvatar = null; pendingSelection = null
                conversationId = null; screen = "home"
                if (privacy) locked = true
            }
        }
        if (visible && mayShowSession) error = e.message ?: "操作失败，请检查网络后重试"
    }
}
