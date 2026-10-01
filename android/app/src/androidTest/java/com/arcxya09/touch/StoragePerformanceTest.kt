package com.arcxya09.touch

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** A repeatable scale fixture. Device-specific results are evidence, not a physical-device release pass. */
class StoragePerformanceTest {
    @Test fun recordsFiftyThousandMessagePageAndBoundedCleanupTimings() = runBlocking {
        withContext(Dispatchers.IO) {
            val app = ApplicationProvider.getApplicationContext<TouchApp>()
            val name = "performance-${UUID.randomUUID()}.db"
            val db = EncryptedDatabase.open(app, app.vault, name)
            val repo = Repository(app, { db }, app.secureStore)
            val owner = UUID.randomUUID().toString()
            val now = System.currentTimeMillis() / 1000
            try {
                db.cache().put(TouchDatabase.Item("meta", "owner", owner))
                repo.purge()
                val seedStart = SystemClock.elapsedRealtimeNanos()
                db.runInTransaction {
                    for (seq in 1L..50000L) {
                        val message = JSONObject().put("id", "m$seq").put("conversation_id", "scale")
                            .put("sender_id", owner).put("client_id", "client$seq").put("kind", "text")
                            .put("text", "合成性能数据").put("seq", seq).put("created_at", now)
                        db.cache().put(TouchDatabase.Item("message", "m$seq", message.toString()))
                    }
                }
                val seedMs = (SystemClock.elapsedRealtimeNanos() - seedStart) / 1e6
                repeat(10) { repo.messages("scale") }
                val durations = (0 until 100).map { page ->
                    val before = 50001L - page * 50
                    val start = SystemClock.elapsedRealtimeNanos()
                    val messages = repo.messages("scale", before)
                    val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    assertEquals(50, messages.size)
                    assertEquals(before - 1, messages.last().seq)
                    ms
                }.sorted()
                val cleanupStart = SystemClock.elapsedRealtimeNanos()
                repo.clearLocalHistory()
                val cleanupMs = (SystemClock.elapsedRealtimeNanos() - cleanupStart) / 1e6
                assertTrue(repo.messages("scale").isEmpty())
                val record = JSONObject().put("schema", 1).put("rows", 50000).put("pageSize", 50).put("samples", 100)
                    .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("buildType", BuildConfig.BUILD_TYPE).put("pageP50Ms", durations[49]).put("pageP95Ms", durations[94])
                    .put("pageMaxMs", durations.last()).put("seedMs", seedMs).put("clearMs", cleanupMs)
                    .put("formalPerformanceGatePassed", false)
                File(app.filesDir, "storage-performance.json").writeText(record.toString(2))
            } finally { repo.stop(); db.close(); app.deleteDatabase(name) }
        }
    }
}
