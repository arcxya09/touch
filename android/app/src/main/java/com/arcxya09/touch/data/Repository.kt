package com.arcxya09.touch.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.arcxya09.touch.BuildConfig
import com.arcxya09.touch.TouchApp
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class Repository(private val context: Context, private val database: () -> TouchDatabase, secure: SecureStore) {
    val db get() = database()
    private val app get() = context.applicationContext as TouchApp
    val retention get() = app.retention
    private val files by lazy { EncryptedAttachments(context, app.vault) }
    @Volatile private var localOwner: String? = null
    private val accessGeneration = java.util.concurrent.atomic.AtomicLong()
    val contentRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val purgeLock = Mutex()
    val api = Api(secure)
    private val syncLock = Mutex()
    private val cache get() = db.cache()
    val connection = ConnectionHealth { android.os.SystemClock.elapsedRealtime() }
    private val networkManager = context.getSystemService(android.net.ConnectivityManager::class.java)
    private var network: android.net.Network? = null
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(value: android.net.Network) { synchronized(this@Repository) {
            if (network != value) { network = value; recheck() }
        } }
        override fun onLost(value: android.net.Network) { synchronized(this@Repository) {
            if (network == value) { network = null; recheck() }
        } }
    }
    init { networkManager.registerDefaultNetworkCallback(networkCallback) }
    private var socket: WebSocket? = null
    private var socketToken: String? = null
    private var socketEvent: (() -> Unit)? = null
    private val initLock = Mutex()
    private var initialized = false
    @Volatile var onIncoming: ((List<ChatMessage>) -> Unit)? = null

    suspend fun initialize() = initLock.withLock { withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        purge()
        files.folder.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { check(it.delete()) }
        runCatching { api.load() }.onFailure { api.save(null); clearLocal() }
        connection.detailed(cache.get("meta", "diagnostics")?.json == "true")
        initialized = true
    } }
    suspend fun login(username: String, password: String) = syncLock.withLock { withContext(Dispatchers.IO) {
        val session = api.json("/api/v1/auth/login", "POST", JSONObject().put("username", username).put("password", password), false)
        app.alertSettings.disable()
        com.arcxya09.touch.notifications.AlertService.stop(app)
        val owner = cache.get("meta", "owner")?.json
        if (owner != session.getJSONObject("user").getString("id")) clearLocal()
        cache.put(TouchDatabase.Item("meta", "owner", session.getJSONObject("user").getString("id")))
        localOwner = session.getJSONObject("user").getString("id")
        purge()
        api.save(session)
    } }
    suspend fun clearLocal() = purgeLock.withLock { withContext(Dispatchers.IO) {
        accessGeneration.incrementAndGet()
        localOwner?.let { retention.cutoff(it) }
        localOwner = null
        db.runInTransaction { cache.clear(); cache.clearPending() }
        File(context.cacheDir, "attachments").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        files.folder.listFiles()?.filter { it.isFile }?.forEach { check(it.delete()) }
    } }
    suspend fun visibilityFloor() = withContext(Dispatchers.IO) { cutoff() }
    private fun cutoff(): Long = localOwner?.let {
        maxOf(retention.cutoff(it), cache.get("meta", "local-clear-time")?.json?.toLongOrNull() ?: 0L)
    } ?: Long.MAX_VALUE
    suspend fun enableRetention(enabled: Boolean) = withContext(Dispatchers.IO) {
        retention.setEnabled(enabled, localOwner); purge()
    }
    suspend fun configureRetention(seconds: Long) = withContext(Dispatchers.IO) {
        retention.configure(seconds, localOwner); purge()
    }
    suspend fun purge(): Boolean = purgeLock.withLock { withContext(Dispatchers.IO) {
        localOwner = cache.get("meta", "owner")?.json
        retention.seconds // Validate encrypted policy even before login; never reset a corrupt watermark.
        val floor = cutoff()
        var changed = false
        val removeFiles = mutableSetOf<String>()
        db.runInTransaction {
            for (kind in listOf("message", "draft", "attachment")) {
                do {
                    val expired = cache.expired(kind, floor)
                    expired.forEach {
                        if (it.attachmentId.isNotBlank()) removeFiles.add(it.attachmentId)
                        if (kind == "attachment") removeFiles.add(it.id)
                        cache.remove(kind, it.id); changed = true
                    }
                } while (expired.size == 500)
            }
            cache.pendingItems().forEach {
                if (it.createdAt <= floor) {
                    JSONObject(it.body).optString("attachment_id").takeIf(String::isNotBlank)?.let(removeFiles::add)
                    cache.removePending(it.id); changed = true
                }
            }
            cache.items("conversation").forEach {
                val json = JSONObject(it.json)
                val last = json.optJSONObject("last_message")
                if (last != null && last.getLong("created_at") <= floor) {
                    json.put("last_message", JSONObject.NULL).put("unread", 0)
                    cache.put(TouchDatabase.Item("conversation", it.id, json.toString())); changed = true
                }
            }
            removeFiles.forEach { cache.remove("attachment", it) }
        }
        removeFiles.forEach { id ->
            val file = File(files.folder, id)
            if (file.exists()) check(file.delete()) { "无法清理过期附件" }
        }
        migratePlainAttachments()
        changed
    } }
    private fun attachment(message: ChatMessage): EncryptedAttachment {
        val item = message.file ?: error("附件不存在")
        val owner = localOwner ?: error("请先登录")
        val generation = accessGeneration.get()
        return files.file(item, owner, message.createdAt) {
            accessGeneration.get() == generation && localOwner == owner && message.createdAt > retention.cutoff(owner)
        }
    }
    private fun migratePlainAttachments() {
        // Old versions cached plaintext. Convert retained files before any UI is exposed.
        val legacy = File(context.cacheDir, "attachments")
        val sources = legacy.listFiles()?.filter { it.isFile }.orEmpty()
        if (sources.isEmpty()) return
        val messages = cache.items("message").map { ChatMessage.parse(JSONObject(it.json)) }
        sources.forEach { source ->
            val message = messages.firstOrNull { it.file?.id == source.name }
            if (message != null && message.createdAt > cutoff() && source.length() == message.file!!.size && sha256(source) == message.file.sha256) {
                val target = attachment(message)
                val partial = File(files.folder, source.name + ".part")
                try {
                    target.encryptTo(partial).use { output -> source.inputStream().use { it.copyTo(output) } }
                    check(partial.renameTo(target.encryptedFile))
                } finally { partial.delete() }
            }
            check(source.delete()) { "无法移除旧版明文缓存" }
        }
    }
    suspend fun logout(remote: Boolean = true) = syncLock.withLock {
        withContext(Dispatchers.IO) { app.alertSettings.disable() }
        com.arcxya09.touch.notifications.AlertService.stop(app)
        if (remote) runCatching { api.json("/api/v1/auth/logout", "POST") }
        stop()
        api.save(null)
        clearLocal()
    }
    suspend fun updatePassword(old: String, new: String) {
        val user = api.json("/api/v1/auth/password", "POST", JSONObject().put("current_password", old).put("new_password", new))
        api.updateUser(user)
    }
    private suspend fun acceptUser(user: JSONObject) {
        api.updateUser(user)
    }
    suspend fun updateProfile(name: String, bio: String) = syncLock.withLock {
        acceptUser(api.json("/api/v1/auth/profile", "PATCH", JSONObject().put("display_name", name.trim()).put("bio", bio.trim())))
    }
    suspend fun setReadReceipts(enabled: Boolean) = syncLock.withLock {
        acceptUser(api.json("/api/v1/auth/preferences", "PATCH", JSONObject().put("read_receipts_enabled", enabled)))
    }
    suspend fun removeAvatar() = syncLock.withLock { acceptUser(api.json("/api/v1/auth/avatar", "DELETE")) }
    suspend fun uploadAvatar(uri: Uri) = syncLock.withLock { withContext(Dispatchers.IO) {
        // Bound memory and never create a plaintext thumbnail or upload staging file.
        val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it, 5 * 1024 * 1024) }
            ?: error("无法读取图片")
        require(bytes.size <= 5 * 1024 * 1024) { "头像不能超过 5 MiB" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "avatar", bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())).build()
        api.response("/api/v1/auth/avatar", "POST", body).use { acceptUser(JSONObject(it.body!!.string())) }
    } }
    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= limit) { "图片超过大小限制" }
        }
        return output.toByteArray()
    }
    suspend fun avatarBytes(person: Person): ByteArray? = withContext(Dispatchers.IO) {
        val version = person.avatarVersion ?: return@withContext null
        api.response("/api/v1/profiles/${person.id}/avatar/$version").use { response ->
            val bytes = response.body!!.byteStream().use { readBounded(it, 512 * 1024) }
            require(bytes.size <= 512 * 1024) { "头像过大" }
            bytes
        }
    }
    suspend fun verifyPassword(password: String) { api.json("/api/v1/auth/verify-password", "POST", JSONObject().put("password", password)) }
    suspend fun contacts(): List<ContactItem> = withContext(Dispatchers.IO) { cache.items("contact").map { ContactItem.parse(JSONObject(it.json)) } }
    suspend fun conversations(): List<Conversation> = withContext(Dispatchers.IO) {
        cache.items("conversation").map { Conversation.parse(sanitizeConversation(JSONObject(it.json))) }.sortedByDescending { it.last?.createdAt ?: 0 }
    }
    suspend fun messages(cid: String, before: Long = Long.MAX_VALUE, first: Long? = null, last: Long? = null): List<ChatMessage> = withContext(Dispatchers.IO) {
        val floor = cutoff()
        val rows = if (first != null && last != null) cache.visibleWindow(cid, first, last, floor, api.user?.isAdmin == true)
            else cache.visiblePage(cid, before, floor, api.user?.isAdmin == true).reversed()
        val messages = rows.map { ChatMessage.parse(JSONObject(it.json)) }
        val pending = if (before != Long.MAX_VALUE || first != null) emptyList() else cache.pendingFor(cid, floor).map {
            val json = JSONObject(it.body)
            ChatMessage(it.id, cid, api.user?.id.orEmpty(), it.id, Long.MAX_VALUE, json.getString("kind"), json.optString("text"), it.createdAt,
                json.optString("attachment_id").takeIf(String::isNotBlank)?.let { id -> cache.get("attachment", id)?.let { file -> FileItem.parse(JSONObject(file.json)) } }, true,
                json.optJSONObject("reply_to")?.let(ReplyRef::parse))
        }
        messages + pending
    }
    private fun clearSeq(cid: String): Long = maxOf(cache.get("meta", "clear:$cid")?.json?.toLongOrNull() ?: 0L,
        cache.get("conversation", cid)?.let { JSONObject(it.json).optLong("clear_seq") } ?: 0L)
    fun visible(cid: String, ref: ReplyRef): Boolean = ref.createdAt > cutoff() &&
        ref.seq > clearSeq(cid) && cache.get("hidden-message", ref.id) == null
    private val quoteLocks = Array(16) { Mutex() }
    suspend fun original(cid: String, ref: ReplyRef): ChatMessage? = quoteLocks[(ref.id.hashCode() and Int.MAX_VALUE) % 16].withLock {
        withContext(Dispatchers.IO) {
            if (!visible(cid, ref) || cache.get("recalled-message", ref.id) != null) return@withContext null
            val generation = accessGeneration.get()
            val cached = cache.get("message", ref.id)?.let { ChatMessage.parse(JSONObject(it.json)) }
            if (cached != null && cached.conversationId == cid && cached.createdAt > cutoff()) return@withContext cached.takeUnless { it.kind == "recalled" }
            val json = try { api.json("/api/v1/conversations/$cid/messages/${ref.id}?after_time=${cutoff()}") }
                catch (e: ApiException) { if (e.status == 404) return@withContext null else throw e }
            if (generation != accessGeneration.get() || !visible(cid, ref)) return@withContext null
            val result = ChatMessage.parse(json)
            check(result.conversationId == cid && result.id == ref.id)
            storeMessage(json)
            result.takeUnless { it.kind == "recalled" || generation != accessGeneration.get() || !visible(cid, ref) }
        }
    }
    suspend fun locate(cid: String, ref: ReplyRef): List<ChatMessage> = syncLock.withLock { withContext(Dispatchers.IO) {
        check(visible(cid, ref) && cache.get("recalled-message", ref.id) == null) { "原消息不可用" }
        val result = api.json("/api/v1/conversations/$cid/messages/${ref.id}/context?after_time=${cutoff()}").getJSONArray("messages")
        check(result.length() <= 50)
        db.runInTransaction { for (i in 0 until result.length()) storeMessage(result.getJSONObject(i)) }
        check(visible(cid, ref) && cache.get("recalled-message", ref.id) == null) { "原消息不可用" }
        (0 until result.length()).map { ChatMessage.parse(result.getJSONObject(it)) }.filter { visible(cid, ReplyRef(it.id, it.seq, it.createdAt)) && ((it.kind == "recalled" && api.user?.isAdmin == true) || (it.kind != "recalled" && cache.get("recalled-message", it.id) == null)) }
    } }
    suspend fun draftEnabled(): Boolean = withContext(Dispatchers.IO) { cache.get("meta", "drafts")?.json == "true" }
    suspend fun setDraftEnabled(value: Boolean) = purgeLock.withLock { withContext(Dispatchers.IO) { db.runInTransaction {
        cache.put(TouchDatabase.Item("meta", "drafts", value.toString()))
        if (!value) cache.removeKind("draft")
    } } }
    suspend fun draft(cid: String): JSONObject? = withContext(Dispatchers.IO) {
        cache.get("draft", cid)?.takeIf { it.createdAt > cutoff() }?.let { JSONObject(it.json) }
    }
    suspend fun saveDraft(cid: String, text: String, ref: ReplyRef?, editedAt: Long) = purgeLock.withLock { withContext(Dispatchers.IO) {
        if (draftEnabled() && editedAt > cutoff() && api.user?.id == localOwner && localOwner != null) {
            if (text.isEmpty() && ref == null) cache.remove("draft", cid)
            else cache.put(TouchDatabase.Item("draft", cid, JSONObject().put("text", text).put("reply_to", ref?.json())
                .put("created_at", editedAt).put("conversation_id", cid).toString()))
        }
    }
    }
    private fun storeMessage(json: JSONObject) {
        if (json.optString("kind") != "recalled" && cache.get("recalled-message", json.getString("id")) != null) {
            cache.removePending(json.getString("client_id")); return
        }
        if (json.optString("kind") == "recalled") {
            cache.put(TouchDatabase.Item("recalled-message", json.getString("id"), "true"))
            accessGeneration.incrementAndGet()
            cache.get("message", json.getString("id"))?.let { removeMessageContent(it.id) }
            contentRevision.value++
            app.alerts.clearMessages()
        }
        if (visible(json.getString("conversation_id"), ReplyRef(json.getString("id"), json.getLong("seq"), json.getLong("created_at"))))
            cache.put(TouchDatabase.Item("message", json.getString("id"), json.toString()))
        cache.removePending(json.getString("client_id"))
    }
    private fun sanitizeConversation(json: JSONObject): JSONObject {
        val last = json.optJSONObject("last_message") ?: return json
        if (!visible(json.getString("id"), ReplyRef(last.getString("id"), last.getLong("seq"), last.getLong("created_at"))) ||
            (last.optString("kind") == "recalled" && api.user?.isAdmin != true)) {
            val previous = cache.visiblePage(json.getString("id"), Long.MAX_VALUE, cutoff(), api.user?.isAdmin == true).firstOrNull()
            json.put("last_message", previous?.let { JSONObject(it.json) } ?: JSONObject.NULL).put("unread", 0)
        }
        return json
    }
    private fun removeMessageContent(id: String) {
        accessGeneration.incrementAndGet()
        cache.get("message", id)?.attachmentId?.takeIf(String::isNotBlank)?.let {
            cache.remove("attachment", it)
            File(files.folder, it).let { file -> if (file.exists()) check(file.delete()) { "无法删除本地附件" } }
        }
        cache.remove("message", id)
    }
    suspend fun deleteLocalMessage(message: ChatMessage) = syncLock.withLock { withContext(Dispatchers.IO) {
        db.runInTransaction {
            cache.put(TouchDatabase.Item("hidden-message", message.id, "true"))
            removeMessageContent(message.id)
            cache.items("conversation").forEach {
                cache.put(TouchDatabase.Item("conversation", it.id, sanitizeConversation(JSONObject(it.json)).toString()))
            }
        }
        contentRevision.value++
        app.alerts.clearMessages()
    } }
    suspend fun clearLocalHistory() = syncLock.withLock { purgeLock.withLock { withContext(Dispatchers.IO) {
        accessGeneration.incrementAndGet()
        db.runInTransaction {
            // Keep a local watermark so history and future sync cannot restore cleared content.
            val floor = maxOf(cutoff(), System.currentTimeMillis() / 1000 - 1)
            cache.put(TouchDatabase.Item("meta", "local-clear-time", floor.toString()))
            do {
                val batch = cache.expired("message", Long.MAX_VALUE)
                batch.forEach {
                    // IDs cover the current second without suppressing newly arriving messages in that second.
                    if (it.createdAt > floor) cache.put(TouchDatabase.Item("hidden-message", it.id, "true"))
                    cache.remove("message", it.id)
                }
            } while (batch.size == 500)
            listOf("attachment", "draft").forEach(cache::removeKind)
            cache.clearPending()
            cache.items("conversation").forEach {
                val json = JSONObject(it.json).put("last_message", JSONObject.NULL).put("unread", 0)
                cache.put(TouchDatabase.Item("conversation", it.id, json.toString()))
            }
        }
        files.folder.listFiles()?.filter { it.isFile }?.forEach { check(it.delete()) { "无法删除本地附件" } }
        contentRevision.value++
        app.alerts.clearMessages()
    } } }
    suspend fun recallMessage(message: ChatMessage) {
        api.json("/api/v1/conversations/${message.conversationId}/messages/${message.id}/recall", "POST", JSONObject())
        sync()
    }
    private fun clearMessages(cid: String, through: Long) {
        if (through > (cache.get("meta", "clear:$cid")?.json?.toLongOrNull() ?: 0L)) {
            accessGeneration.incrementAndGet()
            cache.put(TouchDatabase.Item("meta", "clear:$cid", through.toString()))
        }
        cache.cleared(cid, through).forEach {
            if (it.attachmentId.isNotBlank()) {
                cache.remove("attachment", it.attachmentId)
                File(files.folder, it.attachmentId).delete()
            }
            cache.remove("message", it.id)
        }
    }

    private fun deleteLocalConversation(cid: String, through: Long, discardPending: Boolean = false) {
        accessGeneration.incrementAndGet()
        clearMessages(cid, through)
        cache.pendingItems().filter { discardPending && it.conversationId == cid }.forEach {
            JSONObject(it.body).optString("attachment_id").takeIf(String::isNotBlank)?.let { id -> cache.remove("attachment", id) }
            cache.removePending(it.id)
        }
        cache.remove("draft", cid)
        cache.remove("conversation", cid)
    }
    suspend fun sync() = syncLock.withLock { withContext(Dispatchers.IO) {
        val generation = connection.begin()
        try {
        purge()
        if (api.user == null || api.user?.mustChange == true) { connection.failure(generation); return@withContext }
        // Web profile edits and receipt preferences also refresh existing sessions.
        acceptUser(api.json("/api/v1/auth/me"))
        val incoming = ArrayDeque<ChatMessage>()
        var cursor = cache.get("meta", "cursor")?.json?.toLongOrNull() ?: 0L
        do {
            val result = api.json("/api/v1/sync?cursor=$cursor&after_time=${cutoff()}")
            db.runInTransaction {
                val events = result.getJSONArray("events")
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    val payload = event.getJSONObject("payload")
                    when (event.getString("kind")) {
                        "message" -> {
                            storeMessage(payload)
                            if (payload.getString("sender_id") != api.user?.id) {
                                incoming.addLast(ChatMessage.parse(payload))
                                if (incoming.size > 20) incoming.removeFirst()
                            }
                        }
                        "clear" -> if (payload.optBoolean("deleted")) deleteLocalConversation(payload.getString("conversation_id"), payload.getLong("seq"))
                            else clearMessages(payload.getString("conversation_id"), payload.getLong("seq"))
                    }
                }
            }
            cursor = result.getLong("cursor")
        } while (result.getBoolean("has_more"))
        val conversations = JSONArray(api.text("/api/v1/conversations?after_time=${cutoff()}"))
        val contacts = JSONArray(api.text("/api/v1/contacts"))
        db.runInTransaction {
            cache.put(TouchDatabase.Item("meta", "cursor", cursor.toString()))
            cache.removeKind("conversation"); cache.removeKind("contact")
            for (i in 0 until conversations.length()) {
                val item = conversations.getJSONObject(i)
                item.optJSONObject("last_message")?.let { last ->
                    if (last.getLong("created_at") <= cutoff()) item.put("last_message", JSONObject.NULL).put("unread", 0)
                }
                cache.put(TouchDatabase.Item("conversation", item.getString("id"), sanitizeConversation(item).toString()))
                clearMessages(item.getString("id"), item.getLong("clear_seq"))
            }
            for (i in 0 until contacts.length()) {
                val item = contacts.getJSONObject(i)
                cache.put(TouchDatabase.Item("contact", item.getString("id"), item.toString()))
            }
        }
        purge()
        val eligible = incoming.filter { message ->
            val conversation = cache.get("conversation", message.conversationId)?.json?.let(::JSONObject)
            message.kind != "recalled" && cache.get("message", message.id)?.let { JSONObject(it.json).optString("kind") != "recalled" } == true && conversation != null &&
                message.seq > conversation.optLong("read_seq") && message.seq > conversation.optLong("clear_seq")
        }
        if (eligible.isNotEmpty()) onIncoming?.invoke(eligible)
        connection.success(generation, cache.get("meta", "cursor")?.json?.toLongOrNull() ?: 0L)
        } catch (e: Exception) { connection.failedWith(e); connection.failure(generation); throw e }
    } }
    suspend fun history(cid: String, before: Long? = null): Boolean = syncLock.withLock { withContext(Dispatchers.IO) {
        purge()
        val result = api.json("/api/v1/conversations/$cid/messages?after_time=${cutoff()}" + (before?.let { "&before=$it" } ?: ""))
        db.runInTransaction {
            val messages = result.getJSONArray("messages")
            for (i in 0 until messages.length()) storeMessage(messages.getJSONObject(i))
        }
        result.getBoolean("has_more")
    } }
    val sending = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    suspend fun send(cid: String, text: String = "", attachment: FileItem? = null, reply: ReplyRef? = null,
                     queued: suspend () -> Unit = {}): String = withContext(Dispatchers.IO) {
        if (reply != null) check(original(cid, reply) != null) { "原消息不可用，请移除引用后再发送" }
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("client_id", id).put("kind", attachment?.kind ?: "text").put("text", text)
        if (attachment != null) body.put("attachment_id", attachment.id)
        if (reply != null) body.put("reply_to_id", reply.id).put("reply_to", reply.json())
        db.runInTransaction {
            cache.pending(TouchDatabase.Outbox(id, cid, body.toString(), System.currentTimeMillis() / 1000))
            cache.remove("draft", cid)
        }
        queued()
        retry(id)
        id
    }
    suspend fun retry(id: String) = syncLock.withLock { withContext(Dispatchers.IO) {
        purge()
        val item = cache.pendingItems().firstOrNull { it.id == id } ?: return@withContext
        val body = JSONObject(item.body)
        body.optJSONObject("reply_to")?.let { check(original(item.conversationId, ReplyRef.parse(it)) != null) { "原消息不可用，请删除待发送项并重新编辑" } }
        body.put("after_time", cutoff()).remove("reply_to")
        sending.value = sending.value + id
        try {
            val result = api.json("/api/v1/conversations/${item.conversationId}/messages", "POST", body)
            db.runInTransaction { storeMessage(result) }
        } finally { sending.value = sending.value - id }
    } }
    suspend fun discard(id: String) = withContext(Dispatchers.IO) { cache.removePending(id) }
    suspend fun read(cid: String, seq: Long) { api.json("/api/v1/conversations/$cid/read", "POST", JSONObject().put("seq", seq)) }
    suspend fun deleteConversation(cid: String) = syncLock.withLock { withContext(Dispatchers.IO) {
        val result = api.json("/api/v1/conversations/$cid", "DELETE")
        db.runInTransaction { deleteLocalConversation(cid, result.getLong("clear_seq"), discardPending = true) }
        purge()
    } }
    suspend fun search(username: String): Person {
        val encoded = java.net.URLEncoder.encode(username.trim(), "UTF-8")
        return Person.parse(api.json("/api/v1/contacts/search?username=$encoded"))
    }
    suspend fun request(person: Person) { api.json("/api/v1/contacts/requests", "POST", JSONObject().put("user_id", person.id)); sync() }
    suspend fun contactAction(id: String, action: String) { api.json("/api/v1/contacts/$id", "POST", JSONObject().put("action", action)); sync() }

    suspend fun upload(uri: Uri, kind: String, onProgress: (Float) -> Unit): FileItem = withContext(Dispatchers.IO) {
        var name = "文件"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0) ?: name
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        val limit = if (kind == "image") 20L * 1024 * 1024 else 100L * 1024 * 1024
        require(size <= limit) { "文件超过大小限制" }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val coroutineContext = currentCoroutineContext()
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = ByteArray(65536)
                    var sent = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        sent += count
                        require(sent <= limit) { "文件超过大小限制" }
                        sink.write(bytes, 0, count)
                        if (size > 0) onProgress(sent.toFloat() / size)
                    }
                } ?: error("无法读取文件")
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", name, body).build()
        val result = api.response("/api/v1/files?kind=$kind", "POST", multipart).use { JSONObject(it.body!!.string()) }
        val item = FileItem.parse(result)
        result.put("local_created_at", System.currentTimeMillis() / 1000)
        cache.put(TouchDatabase.Item("attachment", item.id, result.toString()))
        item
    }
    private val downloadLocks = Array(16) { Mutex() }
    suspend fun download(message: ChatMessage, onProgress: (Float) -> Unit): EncryptedAttachment {
        val id = message.file?.id ?: error("附件不存在")
        return downloadLocks[(id.hashCode() and Int.MAX_VALUE) % downloadLocks.size].withLock { downloadLocked(message, onProgress) }
    }
    private suspend fun downloadLocked(message: ChatMessage, onProgress: (Float) -> Unit): EncryptedAttachment = withContext(Dispatchers.IO) {
        purge()
        check(visible(message.conversationId, ReplyRef(message.id, message.seq, message.createdAt))) { "消息已删除" }
        check(cache.get("recalled-message", message.id) == null && cache.get("message", message.id)?.let { JSONObject(it.json).optString("kind") != "recalled" } != false) { "消息已撤回" }
        val item = message.file ?: error("附件不存在")
        val target = attachment(message)
        target.checkAccess()
        if (target.encryptedFile.exists()) {
            val valid = runCatching { target.input().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536); var size = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; size += n; digest.update(buffer, 0, n) }
                size == item.size && digest.digest().joinToString("") { "%02x".format(it) } == item.sha256
            } }.getOrDefault(false)
            if (valid) return@withContext target
            check(target.encryptedFile.delete())
        }
        val partial = File(files.folder, "${item.id}.part")
        var count = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            target.checkAccess()
            api.response("/api/v1/files/${item.id}?after_time=${cutoff()}").use { response ->
                response.body!!.byteStream().use { input -> target.encryptTo(partial).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        target.checkAccess()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= item.size) { "文件大小异常" }
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        onProgress(count.toFloat() / item.size)
                    }
                } }
            }
            require(count == item.size && digest.digest().joinToString("") { "%02x".format(it) } == item.sha256) { "文件校验失败" }
            target.checkAccess()
            check(partial.renameTo(target.encryptedFile)) { "无法保存文件" }
            target
        } finally { partial.delete() }
    }
    @Synchronized fun connect(onEvent: () -> Unit) {
        socketEvent = onEvent
        val access = api.session?.optString("access_token") ?: return
        if (socket != null && socketToken == access) return
        disconnect()
        socketEvent = onEvent
        socketToken = access
        connection.opening()
        val url = BuildConfig.API_BASE.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
        socket = api.socketClient.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer $access").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { synchronized(this@Repository) {
                if (socket === webSocket) { connection.opened(); socketEvent?.invoke() }
            } }
            override fun onMessage(webSocket: WebSocket, text: String) { synchronized(this@Repository) {
                if (socket === webSocket) {
                    val cursor = runCatching { JSONObject(text).getLong("cursor") }.getOrNull()
                    if (cursor != null) connection.event(cursor)
                    socketEvent?.invoke()
                }
            } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { ended(webSocket); webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@Repository) { if (socket === webSocket) connection.failedWith(t) }
                ended(webSocket)
            }
            private fun ended(webSocket: WebSocket) { synchronized(this@Repository) {
                if (socket === webSocket) { socket = null; socketToken = null; connection.invalidate(); socketEvent?.invoke() }
            } }
        })
    }
    @Synchronized fun disconnect(keepListener: Boolean = false) {
        val old = socket; socket = null; socketToken = null; if (!keepListener) socketEvent = null
        connection.invalidate(); old?.cancel()
    }
    @Synchronized fun recheck() {
        val wake = socketEvent
        disconnect()
        socketEvent = wake
        wake?.invoke()
    }
    fun stop() { disconnect(); api.closeConnections() }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
