package com.arcxya09.touch

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arcxya09.touch.data.*
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
    private var work: Job? = null
    private var updateJob: Job? = null
    private var foreground = false
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
    var connected by mutableStateOf(false); private set
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
    val mayShowChat get() = initialized && cacheReady && !storageError && (!privacy || !locked)

    init {
        // A timer that expired while this process was absent must not ring on reopening.
        pomodoro.schedule()
        viewModelScope.launch {
            runCatching {
                val config = app.secureStore.read("privacy")?.let(::JSONObject)
                privacy = marker.exists() || config?.optBoolean("enabled") == true
                pattern = config?.optString("pattern").orEmpty()
            }.onFailure { privacy = true }
            try {
                repository.initialize()
                retentionEnabled = withContext(Dispatchers.IO) { repository.retention.enabled }
                retentionSeconds = withContext(Dispatchers.IO) { repository.retention.seconds }
                user = repository.api.user
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
                if (initialized && foreground && !storageError) {
                    try {
                        if (repository.purge()) reloadLocal()
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
        viewModelScope.launch {
            try { repository.purge(); reloadLocal(); cacheReady = true; if (foreground && mayShowChat) startForegroundWork() }
            catch (_: Exception) { storageError = true }
        }
    }
    fun background() {
        foreground = false
        cacheReady = false
        if (privacy) locked = true
        showUpdate = false
        syncJob?.cancel(); work?.cancel(); updateJob?.cancel()
        repository.stop()
        busy = false; transfer = null; updateProgress = null; connected = false
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
    fun unlock(points: List<Int>) {
        if (!privacy || !initialized || pattern.isEmpty()) return
        if (System.currentTimeMillis() < attempts.getLong("until", 0)) return
        if (MessageDigest.isEqual(Pattern.encode(points).toByteArray(), pattern.toByteArray())) {
            attempts.edit().clear().commit()
            locked = false; error = null
            startForegroundWork()
        } else {
            val count = attempts.getInt("failures", 0) + 1
            attempts.edit().putInt("failures", if (count >= 5) 0 else count)
                .putLong("until", if (count >= 5) System.currentTimeMillis() + 30000 else 0).commit()
        }
    }
    fun setPrivacy(newPattern: String) = action {
        withContext(Dispatchers.IO) { marker.writeText("1") }
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", newPattern).toString())
        pattern = newPattern
        privacy = true; locked = true; screen = "home"; showUpdate = false
        syncJob?.cancel(); repository.stop()
    }
    fun disablePrivacy(password: String) = action {
        repository.verifyPassword(password)
        app.secureStore.write("privacy", JSONObject().put("enabled", false).toString())
        withContext(Dispatchers.IO) { check(!marker.exists() || marker.delete()) { "无法保存隐私设置" } }
        privacy = false; locked = false; pattern = ""
    }
    fun verifyPrivacyPassword(password: String, success: () -> Unit) = action { repository.verifyPassword(password); success() }
    fun login(name: String, password: String) = action {
        repository.login(name, password); user = repository.api.user
        screen = "home"; error = null; startForegroundWork()
    }
    fun password(old: String, new: String) = action {
        repository.updatePassword(old, new); user = repository.api.user; screen = "home"; startForegroundWork()
    }
    fun logout() = action {
        syncJob?.cancel(); repository.logout(); user = null
        conversations = emptyList(); contacts = emptyList(); messages = emptyList(); conversationId = null; pendingAvatar = null; pendingSelection = null; preview = null
        screen = "home"; if (privacy) locked = true
    }
    private fun startForegroundWork() {
        if (!foreground || !mayShowChat) return
        autoUpdate()
        if (user == null || user?.mustChange == true || syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            while (isActive && foreground && mayShowChat) {
                try {
                    repository.sync(); connected = true; reloadLocal()
                    repository.connect {
                        if (foreground && mayShowChat) launch {
                            try { repository.sync(); reloadLocal() } catch (e: Exception) { if (e !is CancellationException) handleError(e, false) }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    connected = false; handleError(e, false)
                }
                delay(15000)
            }
        }
    }
    private suspend fun reloadLocal() {
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
    fun clearHistory() = action { conversationId?.let { repository.clear(it) }; reloadLocal() }
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
    fun action(block: suspend () -> Unit) {
        if (busy || !mayShowChat) return
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
        if (visible && mayShowChat) error = e.message ?: "操作失败，请检查网络后重试"
    }
}
