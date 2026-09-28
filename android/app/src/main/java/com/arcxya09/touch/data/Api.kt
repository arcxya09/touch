package com.arcxya09.touch.data

import com.arcxya09.touch.BuildConfig
import com.arcxya09.touch.security.SecureStore
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

class Api(private val secure: SecureStore) {
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    @Volatile var session: JSONObject? = null
        private set
    private val refreshLock = Mutex()
    val user: Person? get() = session?.optJSONObject("user")?.let(Person::parse)

    suspend fun load() { session = secure.read("session")?.let(::JSONObject) }
    suspend fun save(value: JSONObject?) { secure.write("session", value?.toString()); session = value }

    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    suspend fun response(path: String, method: String = "GET", body: RequestBody? = null, authenticated: Boolean = true): Response {
        val originalToken = session?.optString("access_token")
        fun request() = Request.Builder().url(BuildConfig.API_BASE + path).method(method, body).apply {
            if (authenticated) session?.optString("access_token")?.let { header("Authorization", "Bearer $it") }
        }.build()
        var result = execute(request())
        if (authenticated && result.code == 401 && result.header("X-Auth-Reason") == "expired") {
            result.close()
            refreshLock.withLock {
                if (session?.optString("access_token") == originalToken) {
                    val refresh = session?.optString("refresh_token") ?: throw ApiException(401, "请重新登录")
                    val renewed = json("/api/v1/auth/refresh", "POST", JSONObject().put("refresh_token", refresh), false)
                    save(renewed)
                }
            }
            result = execute(request())
        }
        if (!result.isSuccessful) {
            val text = result.body?.string().orEmpty()
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

    suspend fun text(path: String, method: String = "GET", json: JSONObject? = null, auth: Boolean = true): String {
        val body = if (method != "GET") (json ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()) else null
        return response(path, method, body, auth).use { it.body?.string().orEmpty() }
    }
    suspend fun json(path: String, method: String = "GET", json: JSONObject? = null, auth: Boolean = true) = JSONObject(text(path, method, json, auth))
    fun closeConnections() { client.dispatcher.cancelAll() }
}
