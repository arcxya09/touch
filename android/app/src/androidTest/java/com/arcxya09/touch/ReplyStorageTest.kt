package com.arcxya09.touch

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.*
import com.arcxya09.touch.security.LocalVault
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ReplyStorageTest {
    private val app get() = ApplicationProvider.getApplicationContext<TouchApp>()
    @Test fun realV1MigrationPreservesRowsAndBuildsIndexesWithoutPlaintext() {
        val name = "v1-${UUID.randomUUID()}.db"
        val vault = LocalVault(object : ContextWrapper(app) {
            private val folder = File(app.noBackupFilesDir, name).apply { mkdirs() }
            override fun getNoBackupFilesDir() = folder
        })
        val secret = "migration109-secret"
        val json = JSONObject().put("conversation_id", "c").put("seq", 7).put("created_at", 123).put("text", secret).toString()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE items(kind TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id))")
            db.execSQL("CREATE TABLE outbox(id TEXT NOT NULL PRIMARY KEY,conversationId TEXT NOT NULL,body TEXT NOT NULL,createdAt INTEGER NOT NULL)")
            db.execSQL("INSERT INTO items VALUES('message','m',?)", arrayOf(json))
            db.execSQL("INSERT INTO outbox VALUES('p','c','{}',124)")
            db.version = 1
        }
        EncryptedDatabase.open(app, vault, name).let { db ->
            try {
                assertEquals(json, db.cache().page("c", Long.MAX_VALUE, 0).single().json)
                assertEquals("p", db.cache().pendingItems().single().id)
                assertTrue(db.cache().page("c", Long.MAX_VALUE, 123).isEmpty())
            } finally { db.close() }
        }
        assertFalse(app.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        app.deleteDatabase(name)
    }

    @Test fun fiftyThousandRowsUseBoundedIndexedPagesAndExpiryQueries() {
        val name = "large-${UUID.randomUUID()}.db"
        val db = EncryptedDatabase.open(app, app.vault, name)
        try {
            for (count in listOf(10000, 50000)) {
                val start = if (count == 10000) 1 else 10001
                db.runInTransaction {
                    for (i in start..count) db.cache().put(TouchDatabase.Item("message", "m$i",
                        JSONObject().put("conversation_id", if (i % 2 == 0) "a" else "b")
                            .put("seq", i).put("created_at", i).put("text", "private-$i").toString()))
                }
                val begin = android.os.SystemClock.elapsedRealtime()
                val page = db.cache().page("a", Long.MAX_VALUE, 0)
                assertEquals(50, page.size)
                assertEquals(count.toLong(), page.first().seq)
                assertTrue(page.all { it.conversationId == "a" })
                android.util.Log.i("TouchTest", "rows=$count pageMs=${android.os.SystemClock.elapsedRealtime()-begin}")
            }
            db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT * FROM items WHERE kind='message' AND conversationId='a' AND seq<50000 AND createdAt>0 ORDER BY seq DESC LIMIT 50").use { rows ->
                val plans = mutableListOf<String>(); while (rows.moveToNext()) plans.add(rows.getString(3))
                assertTrue(plans.any { "index_items_kind_conversationId_seq" in it })
                assertFalse(plans.any { it.startsWith("SCAN items") })
            }
            assertEquals(4, db.cache().expired("message", 4).size)
            assertEquals(50, db.cache().window("a", 20000, 20100, 0).size)
            assertTrue(db.cache().page("a", 100, 100).isEmpty())
            val start = android.os.SystemClock.elapsedRealtime()
            var removed = 0
            db.runInTransaction {
                do {
                    val expired = db.cache().expired("message", 50000)
                    assertTrue(expired.size <= 500)
                    expired.forEach { db.cache().remove("message", it.id); removed++ }
                } while (expired.size == 500)
            }
            assertEquals(50000, removed)
            assertTrue(db.cache().page("a", Long.MAX_VALUE, 0).isEmpty())
            android.util.Log.i("TouchTest", "expired=50000 cleanupMs=${android.os.SystemClock.elapsedRealtime()-start}")
        } finally { db.close(); app.deleteDatabase(name) }
    }
}
