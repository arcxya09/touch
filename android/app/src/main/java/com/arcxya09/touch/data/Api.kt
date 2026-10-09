package com.arcxya09.touch.data

import com.arcxya09.touch.BuildConfig
import com.arcxya09.touch.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.ForwardingSource
import okio.Buffer
import okio.buffer

class CredentialStorageException(cause: Exception) : Exception("无法保存登录凭据，请检查设备存储空间后重试", cause)

class Api(secure: SecureStore, private val baseUrl: String = BuildConfig.API_BASE,
          private val credentials: SecureSessionStore = SecureSessionStore(secure)) {
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    private val jsonClient = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    val socketClient = client.newBuilder().pingInterval(10, TimeUnit.SECONDS).build()
    val session: JSONObject? get() = credentials.value
    private val refreshLock = Mutex()
    val user: Person? get() = session?.optJSONObject("user")?.let(Person::parse)

    suspend fun load() = credentials.load()
    fun sealCredentials() = credentials.seal()
    suspend fun save(value: JSONObject?) = refreshLock.withLock { saveLocked(value) }
    private suspend fun saveLocked(value: JSONObject?) {
        try { credentials.save(value) }
        catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // A failed token refresh write is local storage failure, never ambiguous delivery.
            throw CredentialStorageException(e)
        }
    }
    suspend fun updateUser(value: JSONObject) = refreshLock.withLock {
        session?.takeIf { it.getJSONObject("user").getString("id") == value.getString("id") }?.let {
            saveLocked(JSONObject(it.toString()).put("user", value))
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun execute(request: Request, transport: OkHttpClient = client): Response {
        val owner = currentCoroutineContext()[Job]
        return suspendCancellableCoroutine { continuation ->
        val call = transport.newCall(request)
        // The response body may outlive header delivery. Keep request cancellation bound until close().
        val cancellation = owner?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) call.cancel()
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cancellation?.dispose()
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                val original = response.body
                val bound = if (original == null) {
                    cancellation?.dispose(); response
                } else response.newBuilder().body(object : ResponseBody() {
                    private val stream = object : ForwardingSource(original.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long = try {
                            owner?.ensureActive(); super.read(sink, byteCount)
                        } catch (e: IOException) { owner?.ensureActive(); throw e }
                        override fun close() { try { super.close() } finally { cancellation?.dispose() } }
                    }.buffer()
                    override fun contentType() = original.contentType()
                    override fun contentLength() = original.contentLength()
                    override fun source() = stream
                }).build()
                if (continuation.isActive) continuation.resume(bound) { _, value, _ -> value.close() } else bound.close()
            }
        })
        }
    }

    suspend fun response(path: String, method: String = "GET", body: RequestBody? = null, authenticated: Boolean = true, bounded: Boolean = false): Response {
        val owner = user?.id
        val transport = if (bounded) jsonClient else client
        fun request() = Request.Builder().url(baseUrl + path).method(method, body).apply {
            header("X-Touch-Capabilities", "recall-v1")
            if (authenticated) session?.optString("access_token")?.let { header("Authorization", "Bearer $it") }
        }.build()
        val first = request()
        val originalToken = first.header("Authorization")?.removePrefix("Bearer ")
        var result = execute(first, transport)
        if (authenticated && result.code == 401) {
            val expired = result.header("X-Auth-Reason") == "expired"
            // Wait for any in-flight refresh before deciding whether "revoked" is final.
            val retry = try { refreshLock.withLock {
                if (user?.id != owner || session == null) false
                else if (session?.optString("access_token") != originalToken) true
                else if (expired) {
                    result.close()
                    val refresh = session?.optString("refresh_token") ?: throw ApiException(401, "请重新登录")
                    val renewed = json("/api/v1/auth/refresh", "POST", JSONObject().put("refresh_token", refresh), false)
                    saveLocked(renewed)
                    true
                } else false
            } } catch (e: Exception) { result.close(); throw e }
            if (retry) { result.close(); result = execute(request(), transport) }
        }

        if (!result.isSuccessful) {
            val text = withContext(Dispatchers.IO) { result.body?.string().orEmpty() }
            val error = runCatching { JSONObject(text).opt("detail") }.getOrNull()
            val message = if (error is String) error else when (result.code) {
                401 -> "登录已失效，请重新登录"
                413 -> "文件超过大小限制"
                429 -> "操作过于频繁，请稍后再试"
                else -> "请求失败（${result.code}）"
            }
            val exception = ApiException(result.code, message, result.header("X-Auth-Reason").orEmpty())
            result.close()
            throw exception
        }
        return result
    }

    suspend fun text(path: String, method: String = "GET", json: JSONObject? = null, auth: Boolean = true): String = withContext(Dispatchers.IO) {
        val body = if (method != "GET") (json ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()) else null
        response(path, method, body, auth, bounded = true).use { it.body?.string().orEmpty() }
    }
    suspend fun json(path: String, method: String = "GET", json: JSONObject? = null, auth: Boolean = true) = JSONObject(text(path, method, json, auth))
    fun closeConnections() { client.dispatcher.cancelAll() }
}
