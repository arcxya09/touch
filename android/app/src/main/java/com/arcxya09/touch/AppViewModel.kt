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

class AppViewModel internal constructor(application: Application, val repository: Repository,
                                       private val conversationReader: ConversationReader = RepositoryConversationReader(repository)) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as TouchApp).repository)
    private val app = application as TouchApp
    val pomodoro = Pomodoro(app)
    private val marker = File(app.filesDir, "privacy.enabled")
    private val exports by lazy { ExportCoordinator(app, repository, app.vault) }
    private val attempts = app.getSharedPreferences("gesture_attempts", Application.MODE_PRIVATE)
    private val updatePreferences = app.getSharedPreferences("update_checks", Application.MODE_PRIVATE)
    var includePrereleases by mutableStateOf(updatePreferences.getBoolean("include_prereleases", false)); private set
    private val displayPreferences = app.getSharedPreferences("display_preferences", Application.MODE_PRIVATE)
    // Keep the existing preference key so upgrades retain the user's choice.
    var lockRotation by mutableStateOf(displayPreferences.getBoolean("lock_chat_rotation", false)); private set
    var rotateImagePreview by mutableStateOf(displayPreferences.getBoolean("rotate_image_preview", false)); private set
    fun setRotationLocked(value: Boolean) {
        displayPreferences.edit().putBoolean("lock_chat_rotation", value).apply()
        lockRotation = value
    }
    fun setImagePreviewRotation(value: Boolean) {
        displayPreferences.edit().putBoolean("rotate_image_preview", value).apply()
        rotateImagePreview = value
    }
    private var pattern = ""
    private var syncJob: Job? = null
    private var resumeJob: Job? = null
    private val operationJobs = mutableMapOf<Operation, Job>()
    private var operations by mutableStateOf(emptyMap<Operation, OperationUiState>())
    fun operationState(operation: Operation) = operations[operation] ?: OperationUiState()
    fun isWorking(operation: Operation) = operationState(operation).working
    private fun operationState(operation: Operation, state: OperationUiState) { operations = operations + (operation to state) }
    private val conversationRequests = ConversationRequests()
    private fun conversationTicket() = conversationRequests.ticket(user?.id, conversationId)
    private fun accepts(ticket: ConversationRequests.Ticket) =
        foreground && mayShowChat && conversationRequests.accepts(ticket, repository.api.user?.id, conversationId)
    private data class DraftSnapshot(val text: String, val quote: ReplyRef?, val editedAt: Long)
    private val draftsInMemory = mutableMapOf<String, DraftSnapshot>()
    private fun rememberDraft() { conversationId?.let { draftsInMemory[it] = DraftSnapshot(draftText, quote, draftEditedAt) } }
    private fun cancelOperation(operation: Operation) {
        operationJobs.remove(operation)?.cancel()
        operationState(operation, OperationUiState())
    }
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
    var destination by mutableStateOf(Screen.Home); private set
    private var navigationGeneration = 0L
    // Compatibility for existing integration tests; all navigation uses the same transition.
    var screen: String
        get() = destination.route
        set(value) { navigate(Screen.fromRoute(value)) }
    fun navigate(target: Screen) {
        if (target == destination) return
        navigationGeneration++
        if (destination == Screen.Contacts) { cancelOperation(Operation.Contacts); foundPerson = null }
        if (destination == Screen.Chat || destination == Screen.Preview) {
            rememberDraft()
            conversationRequests.invalidate()
            cancelOperation(Operation.Conversation)
            if (target != Screen.Preview) { cancelOperation(Operation.Attachment); transfer = null }
        }
        if (target != Screen.Preview) preview = null
        destination = target
        if (target == Screen.Timer) app.alerts.chatVisible = false
    }
    fun back() {
        when (destination) {
            Screen.Home -> if (privacy) hide()
            Screen.Timer -> returnFromTimer()
            else -> navigate(destination.parent())
        }
    }
    var conversations by mutableStateOf(emptyList<Conversation>()); private set
    var conversationsLoaded by mutableStateOf(false); private set
    var contacts by mutableStateOf(emptyList<ContactItem>()); private set
    var draftText by mutableStateOf(""); private set
    var quote by mutableStateOf<ReplyRef?>(null); private set
    var saveDrafts by mutableStateOf(false); private set
    var visibilityFloor by mutableStateOf(0L); private set
    var expiryTick by mutableStateOf(0L); private set
    var sendingIds by mutableStateOf(emptySet<String>()); private set
    var highlightId by mutableStateOf<String?>(null); private set
    var scrollRequest by mutableStateOf(0); private set
    var browsingHistory by mutableStateOf(false); private set
    private var pageBefore = Long.MAX_VALUE
    private var windowFirst: Long? = null
    private var windowLast: Long? = null
    private val draftJobs = mutableMapOf<String, Job>()
    private suspend fun cancelDraftJobs() {
        val pending = draftJobs.values.toList(); draftJobs.clear()
        pending.forEach { it.cancelAndJoin() }
    }
    private var draftEditedAt = 0L
    fun editDraft(value: String) { draftText = value.take(10000); persistDraft() }
    fun quoteMessage(message: ChatMessage) {
        if (!message.pending) { quote = ReplyRef(message.id, message.seq, message.createdAt); persistDraft() }
    }
    fun cancelQuote() { quote = null; persistDraft() }
    private fun persistDraft() {
        draftEditedAt = System.currentTimeMillis() / 1000
        val cid = conversationId ?: return
        draftJobs.remove(cid)?.cancel()
        val owner = user?.id ?: return
        val ticket = conversationTicket()
        val text = draftText; val ref = quote; val editedAt = draftEditedAt
        rememberDraft()
        if (saveDrafts) draftJobs[cid] = viewModelScope.launch {
            delay(250)
            try { if (repository.api.user?.id == owner) repository.saveDraft(cid, text, ref, editedAt) }
            catch (e: Exception) { if (e is CancellationException) throw e; if (accepts(ticket)) error = "草稿保存失败，请稍后重试" }
        }
    }
    fun enableDrafts(value: Boolean) = action {
        cancelDraftJobs(); repository.setDraftEnabled(value); saveDrafts = value
        if (value) persistDraft()
    }
    private suspend fun queuedDraft(ticket: ConversationRequests.Ticket, submittedText: String, submittedQuote: ReplyRef?,
                                    consumeText: Boolean = true): Boolean = withContext(Dispatchers.Main.immediate) {
        if (ticket.owner != repository.api.user?.id) return@withContext false
        ticket.conversationId?.let { cid ->
            if (consumeText) draftsInMemory[cid]?.takeIf { it.text == submittedText && it.quote == submittedQuote }?.let { draftsInMemory.remove(cid) }
        }
        if (!accepts(ticket)) return@withContext false
        val clearSubmitted = consumeText && draftText == submittedText && quote == submittedQuote
        if (clearSubmitted) {
            ticket.conversationId?.let { draftJobs.remove(it)?.cancel() }
            draftText = ""; quote = null
        } else if (!consumeText) {
            // An attachment does not include the composer's text. Only its matching reply is consumed.
            if (quote == submittedQuote) quote = null
            persistDraft()
        }
        pageBefore = Long.MAX_VALUE; windowFirst = null; windowLast = null; browsingHistory = false
        highlightId = null
        // Publish the queued message before asking Compose to scroll to the last item.
        reloadLocal(); scrollRequest++
        clearSubmitted && accepts(ticket) && draftText.isEmpty() && quote == null
    }
    var showDiagnostics by mutableStateOf(false)
    var diagnosticDetails by mutableStateOf(false); private set
    fun diagnostics() { diagnosticDetails = repository.connection.detailed; showDiagnostics = true }
    fun enableDiagnostics(value: Boolean) = action {
        withContext(Dispatchers.IO) { repository.db.cache().put(TouchDatabase.Item("meta", "diagnostics", value.toString())) }
        repository.connection.detailed(value); diagnosticDetails = value
    }
    fun holdHistory() {
        if (windowFirst == null && !browsingHistory && messages.isNotEmpty()) {
            val confirmed = messages.filterNot { it.pending }
            windowFirst = confirmed.minOfOrNull { it.seq }; windowLast = confirmed.maxOfOrNull { it.seq }
            browsingHistory = windowFirst != null
        }
    }
    val hasNewerMessages: Boolean
        get() = (conversations.firstOrNull { it.id == conversationId }?.last?.seq ?: 0L) >
            (messages.filterNot { it.pending }.maxOfOrNull { it.seq } ?: 0L)
    fun reachedLatest() {
        if (!browsingHistory || hasNewerMessages || messages.isEmpty()) return
        // Release the frozen window only when this page actually includes the newest message.
        // Keep the rendered rows and scroll position; no network request or page replacement.
        pageBefore = Long.MAX_VALUE; windowFirst = null; windowLast = null; browsingHistory = false
    }
    fun latest() = action(Operation.Conversation) {
        val ticket = conversationTicket()
        pageBefore = Long.MAX_VALUE; windowFirst = null; windowLast = null; browsingHistory = false
        highlightId = null
        val more = conversationReader.history(ticket.conversationId ?: return@action)
        if (accepts(ticket)) { hasMore = more; reloadLocal(); scrollRequest++ }
    }
    fun locate(ref: ReplyRef) = action(Operation.Conversation) {
        val ticket = conversationTicket()
        val cid = conversationId ?: return@action
        check(withContext(Dispatchers.IO) { repository.visible(cid, ref) }) { "原消息不可用" }
        if (!accepts(ticket)) return@action
        if (messages.any { it.id == ref.id && !it.pending && it.createdAt > visibilityFloor }) {
            holdHistory(); highlightId = ref.id; scrollRequest++
            viewModelScope.launch { delay(1800); if (accepts(ticket) && highlightId == ref.id) highlightId = null }
            return@action
        }
        val rows = repository.locate(cid, ref)
        if (!accepts(ticket)) return@action
        check(rows.any { it.id == ref.id }) { "原消息不可用" }
        windowFirst = rows.minOf { it.seq }; windowLast = rows.maxOf { it.seq }; browsingHistory = true
        messages = rows; hasMore = true; highlightId = ref.id; scrollRequest++
        viewModelScope.launch { delay(1800); if (accepts(ticket) && highlightId == ref.id) highlightId = null }
    }
    var messages by mutableStateOf(emptyList<ChatMessage>()); private set
    var conversationId by mutableStateOf<String?>(null); private set
    var hasMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    val busy get() = operations.any { (operation, state) -> state.working && operation !in setOf(Operation.Update, Operation.Export) }
    var notice by mutableStateOf<String?>(null); private set
    fun clearNotice() { notice = null }
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
    var credentialRecoveryRequired by mutableStateOf(false); private set
    private var cacheReady by mutableStateOf(false)
    var pendingSelection by mutableStateOf<Pair<Uri, String>?>(null)
    var timerError by mutableStateOf<String?>(null); private set
    private var timerFailureReported = false
    fun clearTimerError() { timerError = null }
    var timer by mutableStateOf(runCatching { pomodoro.state() }.getOrElse {
        timerError = "计时状态暂时无法读取，请重新打开应用后重试"
        TimerState(25, 5, false, false, 25 * 60000L, false)
    }); private set
    var update by mutableStateOf<UpdateManifest?>(null)
    var updateApk by mutableStateOf<File?>(null); private set
    var updateProgress by mutableStateOf<Float?>(null); private set
    var showUpdate by mutableStateOf(false)
    var updater: Updater? = null
    private var privacyChoiceMade = false
    var needsPrivacySetup by mutableStateOf(false); private set
    val mayShowChat get() = mayShowSession && !needsPrivacySetup
    val mayShowSession get() = initialized && cacheReady && !storageError && (!privacy || !locked)
    val gate get() = when {
        privacy && locked -> SessionGate.Locked
        storageError -> SessionGate.StorageUnavailable
        !initialized || !cacheReady -> SessionGate.Loading
        credentialRecoveryRequired -> SessionGate.CredentialsRecovery
        needsPrivacySetup -> SessionGate.PrivacySetup
        user == null -> SessionGate.SignedOut
        else -> SessionGate.Ready
    }

    init {
        viewModelScope.launch { repository.connection.state.collect { refreshConnectionStatus() } }
        // A timer that expired while this process was absent must not ring on reopening.
        runCatching { pomodoro.schedule() }.onFailure { timerError = "计时提醒暂时无法安排，请检查系统提醒权限后重试" }
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
                credentialRecoveryRequired = repository.credentialRecoveryRequired
                if (!isWorking(Operation.Export)) {
                    try { exports.recoverInterrupted()?.let { notice = it } }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        operationState(Operation.Export, OperationUiState(OperationStatus.Failed, "上次文件保存状态无法读取"))
                        notice = "上次文件保存状态无法读取，请检查保存位置后重新保存"
                    }
                }
                saveDrafts = repository.draftEnabled()
                viewModelScope.launch {
                    repository.db.cache().changes().collect {
                        if (initialized && foreground && mayShowChat) {
                            try { reloadLocal() }
                            catch (e: Exception) { if (e is CancellationException) throw e; storageError = true }
                        }
                    }
                }
                viewModelScope.launch { repository.sending.collect { sendingIds = it } }
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
                try {
                    val state = pomodoro.state()
                    if (state.running && state.remainingMs == 0L) pomodoro.finish(true)
                    timer = pomodoro.state()
                    timerFailureReported = false
                } catch (_: Exception) {
                    if (!timerFailureReported) timerError = "计时状态暂时无法更新，请重新打开应用后重试"
                    timerFailureReported = true
                }
                refreshConnectionStatus()
                if (initialized && foreground && !storageError) {
                    try {
                        alertOptions = withContext(Dispatchers.IO) { app.alertSettings.read() }
                        backgroundAlerts = AlertService.running
                        if (pendingMessageNotification && !isWorking(Operation.Session)) startForegroundWork()
                        expiryTick = System.currentTimeMillis() / 1000
                        visibilityFloor = repository.visibilityFloor()
                        if (repository.purgeIfDue() || (user != null && repository.api.user == null)) reloadLocal()
                        if (retentionEnabled && draftEditedAt > 0 && draftEditedAt <= expiryTick - retentionSeconds) {
                            conversationId?.let { draftJobs.remove(it)?.cancel(); draftsInMemory.remove(it) }
                            draftText = ""; quote = null
                        }
                        if (foreground && mayShowChat && !isWorking(Operation.Settings) && pendingNotificationEnable) {
                            pendingNotificationEnable = false; enableAlerts(true)
                        }
                        val shownPreview = preview
                        val previewTicket = conversationTicket()
                        if (shownPreview != null && withContext(Dispatchers.IO) { !shownPreview.second.valid() } &&
                            preview === shownPreview && accepts(previewTicket)) {
                            preview = null; if (screen == "preview") screen = "chat"
                        }
                    } catch (e: Exception) { if (e is CancellationException) throw e; storageError = true; cacheReady = false; preview = null; messages = emptyList(); conversations = emptyList() }
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
        viewModelScope.launch(NonCancellable + Dispatchers.IO) { runCatching { repository.checkpointRetention() } }
        rememberDraft()
        conversationRequests.invalidate()
        foreground = false
        resumeJob?.cancel()
        app.alerts.chatVisible = false
        cacheReady = false
        if (privacy) locked = true
        showUpdate = false
        syncJob?.cancel(); operationJobs.keys.toList().filter { it != Operation.Export }.forEach(::cancelOperation)
        if (!AlertService.running) repository.stop()
        transfer = null; updateProgress = null; connectionStatus = ConnectionStatus.CONNECTING
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
    fun setPrivacy(newPattern: String) = action(Operation.Session, allowSetup = true) {
        require(Pattern.valid(newPattern.map { it.digitToIntOrNull() ?: -1 })) { "请设置有效的解锁图案" }
        withContext(Dispatchers.IO) { marker.writeText("1") }
        app.secureStore.write("privacy", JSONObject().put("enabled", true).put("pattern", newPattern).toString())
        privacyChoiceMade = true; needsPrivacySetup = false
        pattern = newPattern
        privacy = true; locked = true; screen = "home"; showUpdate = false
        syncJob?.cancel(); app.alerts.chatVisible = false; if (!AlertService.running) repository.stop()
    }
    fun skipPrivacySetup() = action(Operation.Session, allowSetup = true) {
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
    fun login(name: String, password: String) = action(Operation.Session, allowSetup = true) {
        if (!privacyChoiceMade) {
            // Persist before login: a process death must not bypass the choice.
            app.secureStore.write("privacy", JSONObject().put("pending", true).toString())
            needsPrivacySetup = true
        }
        repository.login(name, password); user = repository.api.user; credentialRecoveryRequired = false
        draftsInMemory.clear(); conversationRequests.invalidate(); reloadLocal()
        screen = "home"; error = null; startForegroundWork()
    }
    fun password(old: String, new: String) = action(Operation.Session, allowSetup = true) {
        repository.updatePassword(old, new); user = repository.api.user; screen = "home"; startForegroundWork()
    }
    fun logout() = action(Operation.Session, allowSetup = true) {
        syncJob?.cancel(); repository.logout(); user = null
        cancelDraftJobs(); draftText = ""; quote = null; draftsInMemory.clear(); conversationRequests.invalidate()
        conversations = emptyList(); contacts = emptyList(); messages = emptyList(); conversationId = null; pendingAvatar = null; pendingSelection = null; preview = null
        screen = "home"; if (privacy) locked = true
    }
    private var pendingMessageNotification = false
    private var notificationConversationId: String? = null
    fun openMessageNotification(id: String?) {
        pendingMessageNotification = true
        notificationConversationId = id
        // Read the persisted privacy choice before navigation; cold starts may still be loading it.
        if (privacy) { hide(); screen = "timer" }
        if (initialized && foreground && mayShowChat) startForegroundWork()
    }
    private fun startForegroundWork() {
        if (!foreground || !mayShowChat) return
        if (pendingNotificationSettings && user != null && user?.mustChange == false) {
            pendingNotificationSettings = false; screen = "notifications"
        }
        if (pendingMessageNotification && !isWorking(Operation.Session) && user != null && user?.mustChange == false) {
            pendingMessageNotification = false
            val id = notificationConversationId
            notificationConversationId = null
            if (id != null && conversations.any { it.id == id }) openConversation(id)
            else screen = "home"
        }
        autoUpdate()
        if (user == null || user?.mustChange == true || syncJob?.isActive == true) return
        // Refresh data on resume without tearing down a healthy shared socket.
        refreshConnectionStatus()
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
                            refreshConnectionStatus(); delay(1000); continue
                        }
                        // Connect before sync; any event arriving during sync stays in this one-slot queue.
                        repository.connect { wake.trySend(Unit) }
                        repository.sync(); reloadLocal(); refreshConnectionStatus()
                        retry = if (repository.connection.state.value.socketOpen) 2000L else (retry * 2).coerceAtMost(15000)
                        withTimeoutOrNull(if (repository.connection.state.value.socketOpen) ConnectionHealth.SYNC_FALLBACK_MS else retry) { wake.receive() }
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
        credentialRecoveryRequired = repository.credentialRecoveryRequired
        if (user == null) {
            conversations = emptyList(); contacts = emptyList(); messages = emptyList()
            conversationsLoaded = true
            return
        }
        val ticket = conversationTicket()
        val floor = repository.visibilityFloor()
        val localConversations = repository.conversations()
        val localContacts = repository.contacts()
        val hasSnapshot = localConversations.isNotEmpty() || withContext(Dispatchers.IO) { repository.db.cache().get("meta", "cursor") != null }
        val localMessages = ticket.conversationId?.let { repository.messages(it, pageBefore, windowFirst, windowLast) }
        if (!conversationRequests.accepts(ticket, repository.api.user?.id, conversationId)) return
        visibilityFloor = floor; conversations = localConversations; contacts = localContacts
        localMessages?.let { messages = it }; conversationsLoaded = hasSnapshot
    }
    fun refreshConversations() = action(Operation.Conversation) { repository.sync(); reloadLocal() }
    private var reportedRead = 0L
    fun markVisibleRead(seq: Long) {
        val id = conversationId ?: return
        if (!foreground || !mayShowChat || screen != "chat" || seq <= reportedRead) return
        val ticket = conversationTicket()
        reportedRead = seq
        viewModelScope.launch {
            try { repository.read(id, seq) }
            catch (e: Exception) { if (e is CancellationException) throw e; if (accepts(ticket)) reportedRead = 0 }
        }
    }
    fun openConversation(id: String) {
        if (!mayShowChat || user == null || isWorking(Operation.Session)) return
        rememberDraft()
        conversationRequests.invalidate()
        cancelOperation(Operation.Conversation)
        cancelOperation(Operation.Attachment); transfer = null
        if (conversationId != id) messages = emptyList()
        navigate(Screen.Chat)
        conversationId = id; reportedRead = 0
        pageBefore = Long.MAX_VALUE; windowFirst = null; windowLast = null; browsingHistory = false
        draftText = ""; quote = null; draftEditedAt = 0; hasMore = false
        val ticket = conversationTicket()
        val remembered = draftsInMemory[id]?.takeIf { it.editedAt > visibilityFloor }
        action(Operation.Conversation) {
            if (remembered != null) {
                draftText = remembered.text; quote = remembered.quote; draftEditedAt = remembered.editedAt
            } else if (saveDrafts) conversationReader.draft(id)?.let {
                if (!accepts(ticket)) return@action
                draftText = it.optString("text"); quote = it.optJSONObject("reply_to")?.let(ReplyRef::parse)
                draftEditedAt = it.optLong("created_at")
            }
            val local = conversationReader.messages(id)
            if (!accepts(ticket)) return@action
            messages = local
            scrollRequest++
            val more = conversationReader.history(id)
            if (!accepts(ticket)) return@action
            hasMore = more
            reloadLocal()
        }
    }
    fun older() = action(Operation.Conversation) {
        val ticket = conversationTicket()
        val id = conversationId ?: return@action
        val before = messages.filterNot { it.pending }.minOfOrNull { it.seq }
        val more = conversationReader.history(id, before)
        if (!accepts(ticket)) return@action
        hasMore = more
        pageBefore = before ?: Long.MAX_VALUE; windowFirst = null; windowLast = null; browsingHistory = true
        reloadLocal(); scrollRequest++
    }
    fun send(text: String, clear: () -> Unit) = action(Operation.Send) {
        val ticket = conversationTicket()
        val id = conversationId ?: return@action
        val reply = quote
        draftJobs.remove(id)?.cancelAndJoin()
        try { repository.send(id, text, reply = reply) {
            val cleared = queuedDraft(ticket, text, reply)
            withContext(Dispatchers.Main.immediate) { if (cleared && accepts(ticket) && draftText.isEmpty() && quote == null) clear() }
        }; repository.sync() }
        finally { reloadLocal() }
    }
    fun retry(id: String) = action(Operation.Send) { try { repository.retry(id); repository.sync() } finally { reloadLocal() } }
    fun deleteLocalMessage(message: ChatMessage) = action(Operation.Send) {
        val ticket = conversationTicket()
        repository.deleteLocalMessage(message)
        if (accepts(ticket)) {
            if (quote?.id == message.id) { quote = null; persistDraft() }
            if (preview?.first?.id == message.file?.id) preview = null
        }
        reloadLocal()
    }
    fun recallMessage(message: ChatMessage) = action(Operation.Send) {
        val ticket = conversationTicket()
        repository.recallMessage(message)
        if (accepts(ticket)) {
            if (quote?.id == message.id) { quote = null; persistDraft() }
            if (preview?.first?.id == message.file?.id) preview = null
        }
        reloadLocal()
    }
    fun clearLocalHistory() = action {
        cancelOperation(Operation.Send); cancelOperation(Operation.Attachment); cancelOperation(Operation.Conversation)
        conversationRequests.invalidate(); cancelDraftJobs(); draftsInMemory.clear()
        repository.clearLocalHistory()
        draftText = ""; quote = null; preview = null; pendingSelection = null; highlightId = null
        pageBefore = Long.MAX_VALUE; windowFirst = null; windowLast = null; browsingHistory = false
        hasMore = false; reloadLocal(); scrollRequest++
    }
    fun discard(id: String) = action(Operation.Send) { repository.discard(id); reloadLocal() }
    fun deleteConversation(id: String) = action(Operation.Send) {
        val ticket = conversationTicket()
        draftJobs.remove(id)?.cancelAndJoin(); repository.deleteConversation(id); draftsInMemory.remove(id)
        if (conversationId == id && accepts(ticket)) {
            conversationId = null; messages = emptyList(); draftText = ""; quote = null; preview = null; pendingSelection = null; hasMore = false
            navigate(Screen.Home)
        }
        reloadLocal()
    }
    fun search(name: String) = action(Operation.Contacts) {
        val owner = user?.id; val generation = navigationGeneration
        foundPerson = null
        val result = repository.search(name)
        if (owner == repository.api.user?.id && destination == Screen.Contacts && navigationGeneration == generation) foundPerson = result
    }
    fun request() = action(Operation.Contacts) {
        val person = foundPerson ?: return@action
        val owner = user?.id; val generation = navigationGeneration
        repository.request(person)
        if (owner == repository.api.user?.id && destination == Screen.Contacts && navigationGeneration == generation && foundPerson == person) foundPerson = null
        reloadLocal()
    }
    fun contactAction(person: Person, operation: String) = action(Operation.Contacts) { repository.contactAction(person.id, operation); reloadLocal() }
    fun sendSelection() = action(Operation.Attachment) {
        val task = currentCoroutineContext()[Job]
        val progress = ProgressUpdates { android.os.SystemClock.elapsedRealtime() }
        val ticket = conversationTicket()
        val selection = pendingSelection ?: return@action
        val id = conversationId ?: return@action
        val reply = quote
        val submittedText = draftText
        transfer = 0f
        try {
            val file = repository.upload(selection.first, selection.second) { value -> if (progress.accept(value)) viewModelScope.launch {
                if (operationJobs[Operation.Attachment] === task && accepts(ticket)) transfer = value
            } }
            if (!accepts(ticket)) return@action
            if (pendingSelection == selection) pendingSelection = null
            draftJobs.remove(id)?.cancelAndJoin()
            repository.send(id, attachment = file, reply = reply) { queuedDraft(ticket, submittedText, reply, consumeText = false) }; repository.sync()
        } finally { if (operationJobs[Operation.Attachment] === task) transfer = null; reloadLocal() }
    }
    fun openFile(message: ChatMessage) = action(Operation.Attachment) {
        val task = currentCoroutineContext()[Job]
        val progress = ProgressUpdates { android.os.SystemClock.elapsedRealtime() }
        val ticket = conversationTicket()
        if (message.conversationId != ticket.conversationId) return@action
        transfer = 0f
        try {
            val path = repository.download(message) { value -> if (progress.accept(value)) viewModelScope.launch {
                if (operationJobs[Operation.Attachment] === task && accepts(ticket)) transfer = value
            } }
            if (accepts(ticket) && destination == Screen.Chat) {
                navigate(Screen.Preview); preview = path.item to path
            }
        } finally { if (operationJobs[Operation.Attachment] === task) transfer = null }
    }
    fun cancelTransfer() { cancelOperation(Operation.Attachment); transfer = null }
    fun prepareExport(file: EncryptedAttachment, name: String, launchPicker: (String) -> Unit) = action(Operation.Export) {
        val owner = user?.id ?: return@action
        val message = messages.firstOrNull { it.file?.id == file.item.id }
            ?: error("原消息不可用，请重新打开附件后保存")
        exports.prepare(file, message, owner)
        launchPicker(name)
    }
    /** The persisted request records the explicit consent given before Android's picker opened. */
    fun completeExport(destination: Uri?) {
        if (isWorking(Operation.Export)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (exports.finish(destination)) notice = "文件已保存"
                operationState(Operation.Export, OperationUiState(OperationStatus.Succeeded))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = e.message ?: "文件保存失败，请重新选择保存位置"
                operationState(Operation.Export, OperationUiState(OperationStatus.Failed, message)); error = message
            } finally {
                if (operationJobs[Operation.Export] === currentCoroutineContext()[Job]) {
                    operationJobs.remove(Operation.Export)
                    if (isWorking(Operation.Export)) operationState(Operation.Export, OperationUiState())
                }
            }
        }
        operationJobs[Operation.Export] = job
        operationState(Operation.Export, OperationUiState(OperationStatus.Running)); job.start()
    }
    private inline fun timerAction(action: () -> Unit) {
        try { action(); timer = pomodoro.state(); timerError = null; timerFailureReported = false }
        catch (_: Exception) { timerError = "计时状态或提醒未能保存，请稍后重试" }
    }
    fun timerStart() = timerAction { pomodoro.start() }
    fun timerPause() = timerAction { pomodoro.pause() }
    fun timerReset() = timerAction { pomodoro.reset() }
    fun timerChoose(rest: Boolean) = timerAction { pomodoro.choose(rest) }
    fun timerConfigure(focus: Int, rest: Int) = timerAction { pomodoro.configure(focus, rest) }
    fun enableRetention(enabled: Boolean) = action {
        repository.enableRetention(enabled)
        retentionEnabled = repository.retention.enabled
        retentionSeconds = repository.retention.seconds
        preview = null
        reloadLocal()
    }
    fun saveProfile(name: String, bio: String) = action(Operation.Profile) {
        repository.updateProfile(name, bio); user = repository.api.user
        repository.sync(); reloadLocal(); if (mayShowChat) notice = "个人资料已保存"
    }
    fun uploadAvatar() = action(Operation.Profile) {
        val uri = pendingAvatar ?: return@action
        repository.uploadAvatar(uri); pendingAvatar = null; user = repository.api.user
        repository.sync(); reloadLocal()
    }
    fun removeAvatar() = action(Operation.Profile) { repository.removeAvatar(); user = repository.api.user; repository.sync(); reloadLocal() }
    fun setReadReceipts(enabled: Boolean) = action(Operation.Profile) {
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

    fun setPrereleaseUpdates(enabled: Boolean) {
        if (isWorking(Operation.Update) || includePrereleases == enabled) return
        updatePreferences.edit().putBoolean("include_prereleases", enabled).remove("next_check").remove("failures").apply()
        includePrereleases = enabled
        update = null; updateApk = null; showUpdate = false
    }
    fun checkUpdate(manual: Boolean = true) {
        if (!foreground || !mayShowChat || destination == Screen.Timer || isWorking(Operation.Update)) return
        val service = updater ?: return
        if (!manual && !service.due()) return
        action(Operation.Update, reportError = manual) {
            val result = service.check(includePrereleases)
            if (foreground && mayShowChat && destination != Screen.Timer) {
                if (update?.versionCode != result?.versionCode) updateApk = null
                update = result; showUpdate = result != null
                if (manual && result == null) notice = "当前已是最新版本"
            }
        }
    }
    private fun autoUpdate() = checkUpdate(false)
    fun downloadUpdate() {
        val manifest = update ?: return
        if (!foreground || !mayShowChat || isWorking(Operation.Update)) return
        action(Operation.Update) {
            val task = currentCoroutineContext()[Job]
            val progress = ProgressUpdates { android.os.SystemClock.elapsedRealtime() }
            updateProgress = 0f
            try {
                updateApk = updater!!.download(manifest) { value -> if (progress.accept(value)) viewModelScope.launch {
                    if (operationJobs[Operation.Update] === task && foreground && mayShowChat) updateProgress = value
                } }
            } finally { if (operationJobs[Operation.Update] === task) updateProgress = null }
        }
    }
    fun installUpdate() = action(Operation.Update) {
        val file = updateApk ?: return@action
        val manifest = update ?: return@action
        val service = updater ?: return@action
        // Hashing and package parsing are IO work; only launch Android's installer on Main.
        try { service.verifyForInstall(file, manifest) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (updateApk == file) updateApk = null
            throw error
        }
        if (foreground && mayShowChat) service.launchInstaller(file)
    }
    fun cancelUpdate() { cancelOperation(Operation.Update); updater?.cancel(); updateProgress = null }
    fun action(operation: Operation = Operation.Settings, allowSetup: Boolean = false,
               reportError: Boolean = true, block: suspend () -> Unit) {
        if (isWorking(operation) || isWorking(Operation.Session) || !mayShowSession || (needsPrivacySetup && !allowSetup)) return
        if (operation == Operation.Session) {
            operationJobs.keys.toList().forEach(::cancelOperation)
            syncJob?.cancel(); conversationRequests.invalidate(); foundPerson = null; notice = null
        }
        val owner = user?.id
        val startedOn = destination
        val startedConversation = conversationTicket()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]
            if (reportError) error = null
            try {
                block()
                if (operationJobs[operation] === ownJob) operationState(operation, OperationUiState(OperationStatus.Succeeded))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (operationJobs[operation] === ownJob) {
                    operationState(operation, OperationUiState(OperationStatus.Failed, e.message ?: "操作失败，请重试"))
                    if (operation == Operation.Session || owner == repository.api.user?.id) {
                        val stillVisible = startedOn == destination && (startedOn !in setOf(Screen.Chat, Screen.Preview) ||
                            conversationRequests.accepts(startedConversation, repository.api.user?.id, conversationId))
                        handleError(e, reportError && stillVisible)
                    }
                }
            } finally {
                if (operationJobs[operation] === ownJob) {
                    operationJobs.remove(operation)
                    if (isWorking(operation)) operationState(operation, OperationUiState())
                }
            }
        }
        operationJobs[operation] = job
        operationState(operation, OperationUiState(OperationStatus.Running))
        job.start()
    }
    private suspend fun handleError(e: Exception, visible: Boolean) {
        if (e is ApiException && e.status == 401) {
            withContext(NonCancellable) {
                syncJob?.cancel(); repository.logout(false); user = null
                conversationRequests.invalidate(); draftsInMemory.clear()
                conversations = emptyList(); contacts = emptyList(); messages = emptyList(); preview = null; pendingAvatar = null; pendingSelection = null
                conversationId = null; screen = "home"
                if (privacy) locked = true
            }
        }
        if (visible && mayShowSession) error = e.message ?: "操作失败，请检查网络后重试"
    }
}
