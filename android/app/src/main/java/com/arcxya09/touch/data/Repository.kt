package com.arcxya09.touch.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.arcxya09.touch.BuildConfig
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class Repository(private val context: Context, val db: TouchDatabase, secure: SecureStore) {
    val api = Api(secure)
    private val syncLock = Mutex()
    private val cache get() = db.cache()
    private var socket: WebSocket? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {
        runCatching { api.load() }.onFailure { api.save(null); clearLocal() }
    }
    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        val session = api.json("/api/v1/auth/login", "POST", JSONObject().put("username", username).put("password", password), false)
        val owner = cache.get("meta", "owner")?.json
        if (owner != session.getJSONObject("user").getString("id")) clearLocal()
        cache.put(TouchDatabase.Item("meta", "owner", session.getJSONObject("user").getString("id")))
        api.save(session)
    }
    suspend fun clearLocal() = withContext(Dispatchers.IO) {
        db.runInTransaction { cache.clear(); cache.clearPending() }
        File(context.cacheDir, "attachments").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }
    suspend fun logout(remote: Boolean = true) {
        if (remote) runCatching { api.json("/api/v1/auth/logout", "POST") }
        stop()
        api.save(null)
        clearLocal()
    }
    suspend fun updatePassword(old: String, new: String) {
        val user = api.json("/api/v1/auth/password", "POST", JSONObject().put("current_password", old).put("new_password", new))
        api.session?.let { api.save(JSONObject(it.toString()).put("user", user)) }
    }
    suspend fun verifyPassword(password: String) { api.json("/api/v1/auth/verify-password", "POST", JSONObject().put("password", password)) }
    suspend fun contacts(): List<ContactItem> = withContext(Dispatchers.IO) { cache.items("contact").map { ContactItem.parse(JSONObject(it.json)) } }
    suspend fun conversations(): List<Conversation> = withContext(Dispatchers.IO) {
        cache.items("conversation").map { Conversation.parse(JSONObject(it.json)) }.sortedByDescending { it.last?.createdAt ?: 0 }
    }
    suspend fun messages(cid: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val messages = cache.items("message").map { ChatMessage.parse(JSONObject(it.json)) }.filter { it.conversationId == cid }
        val pending = cache.pendingItems().filter { it.conversationId == cid }.map {
            val json = JSONObject(it.body)
            ChatMessage(it.id, cid, api.user?.id.orEmpty(), it.id, Long.MAX_VALUE, json.getString("kind"), json.optString("text"), it.createdAt,
                json.optString("attachment_id").takeIf(String::isNotBlank)?.let { id -> cache.get("attachment", id)?.let { file -> FileItem.parse(JSONObject(file.json)) } }, true)
        }
        (messages.sortedBy { it.seq } + pending)
    }
    private fun storeMessage(json: JSONObject) {
        cache.put(TouchDatabase.Item("message", json.getString("id"), json.toString()))
        cache.removePending(json.getString("client_id"))
    }
    private fun clearMessages(cid: String, through: Long) {
        cache.items("message").forEach {
            val message = JSONObject(it.json)
            if (message.getString("conversation_id") == cid && message.getLong("seq") <= through) cache.remove("message", it.id)
        }
    }
    suspend fun sync() = syncLock.withLock { withContext(Dispatchers.IO) {
        if (api.user == null || api.user?.mustChange == true) return@withContext
        do {
            val cursor = cache.get("meta", "cursor")?.json?.toLongOrNull() ?: 0L
            val result = api.json("/api/v1/sync?cursor=$cursor")
            db.runInTransaction {
                val events = result.getJSONArray("events")
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    val payload = event.getJSONObject("payload")
                    when (event.getString("kind")) {
                        "message" -> storeMessage(payload)
                        "clear" -> clearMessages(payload.getString("conversation_id"), payload.getLong("seq"))
                    }
                }
                cache.put(TouchDatabase.Item("meta", "cursor", result.getLong("cursor").toString()))
            }
        } while (result.getBoolean("has_more"))
        val conversations = JSONArray(api.text("/api/v1/conversations"))
        val contacts = JSONArray(api.text("/api/v1/contacts"))
        db.runInTransaction {
            cache.removeKind("conversation"); cache.removeKind("contact")
            for (i in 0 until conversations.length()) {
                val item = conversations.getJSONObject(i)
                cache.put(TouchDatabase.Item("conversation", item.getString("id"), item.toString()))
                clearMessages(item.getString("id"), item.getLong("clear_seq"))
            }
            for (i in 0 until contacts.length()) {
                val item = contacts.getJSONObject(i)
                cache.put(TouchDatabase.Item("contact", item.getString("id"), item.toString()))
            }
        }
    } }
    suspend fun history(cid: String, before: Long? = null): Boolean = withContext(Dispatchers.IO) {
        val result = api.json("/api/v1/conversations/$cid/messages" + (before?.let { "?before=$it" } ?: ""))
        db.runInTransaction {
            val messages = result.getJSONArray("messages")
            for (i in 0 until messages.length()) storeMessage(messages.getJSONObject(i))
        }
        result.getBoolean("has_more")
    }
    suspend fun send(cid: String, text: String = "", attachment: FileItem? = null): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("client_id", id).put("kind", attachment?.kind ?: "text").put("text", text)
        if (attachment != null) body.put("attachment_id", attachment.id)
        cache.pending(TouchDatabase.Outbox(id, cid, body.toString(), System.currentTimeMillis() / 1000))
        retry(id)
        id
    }
    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        val item = cache.pendingItems().firstOrNull { it.id == id } ?: return@withContext
        val result = api.json("/api/v1/conversations/${item.conversationId}/messages", "POST", JSONObject(item.body))
        db.runInTransaction { storeMessage(result) }
    }
    suspend fun discard(id: String) = withContext(Dispatchers.IO) { cache.removePending(id) }
    suspend fun read(cid: String, seq: Long) { api.json("/api/v1/conversations/$cid/read", "POST", JSONObject().put("seq", seq)) }
    suspend fun clear(cid: String) { api.json("/api/v1/conversations/$cid/clear", "POST"); sync() }
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
        cache.put(TouchDatabase.Item("attachment", item.id, result.toString()))
        item
    }
    suspend fun download(item: FileItem, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "attachments").apply { mkdirs() }
        val target = File(folder, item.id)
        if (target.exists() && target.length() == item.size && sha256(target) == item.sha256) return@withContext target
        val partial = File(folder, "${item.id}.part")
        try {
            api.response("/api/v1/files/${item.id}").use { response ->
                response.body!!.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count <= item.size) { "文件大小异常" }
                        output.write(buffer, 0, n)
                        onProgress(count.toFloat() / item.size)
                    }
                } }
            }
            require(partial.length() == item.size && sha256(partial) == item.sha256) { "文件校验失败" }
            check(partial.renameTo(target)) { "无法保存文件" }
            target
        } finally { partial.delete() }
    }
    fun connect(onEvent: () -> Unit) {
        socket?.cancel()
        val access = api.session?.optString("access_token") ?: return
        val url = BuildConfig.API_BASE.replaceFirst("https://", "wss://") + "/ws"
        socket = api.client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer $access").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = onEvent()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onEvent()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onEvent()
        })
    }
    fun stop() { socket?.cancel(); socket = null; api.closeConnections() }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
