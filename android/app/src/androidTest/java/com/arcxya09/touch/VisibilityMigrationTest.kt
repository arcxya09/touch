package com.arcxya09.touch

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.EncryptedDatabase
import com.arcxya09.touch.security.LocalVault
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class VisibilityMigrationTest {
    @Test fun versionTwoRulesMigrateWithoutDestroyingContentOrOutbox() {
        val app = ApplicationProvider.getApplicationContext<TouchApp>()
        val name = "v2-migration-${UUID.randomUUID()}.db"
        val file = app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE items (kind TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,conversationId TEXT NOT NULL DEFAULT '',seq INTEGER NOT NULL DEFAULT 0,createdAt INTEGER NOT NULL DEFAULT 0,attachmentId TEXT NOT NULL DEFAULT '',PRIMARY KEY(kind,id))")
            db.execSQL("CREATE TABLE outbox (id TEXT NOT NULL PRIMARY KEY,conversationId TEXT NOT NULL,body TEXT NOT NULL,createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX index_items_kind_conversationId_seq ON items(kind,conversationId,seq)")
            db.execSQL("CREATE INDEX index_items_kind_createdAt ON items(kind,createdAt)")
            db.execSQL("CREATE INDEX index_items_attachmentId ON items(attachmentId)")
            db.execSQL("CREATE INDEX index_outbox_conversationId_createdAt ON outbox(conversationId,createdAt)")
            db.execSQL("INSERT INTO items(kind,id,json) VALUES('meta','owner','alice'),('hidden-message','deleted','true'),('meta','local-clear-time','123'),('draft','c','{\"text\":\"private\",\"created_at\":456}')")
            db.execSQL("INSERT INTO outbox VALUES('p','c','{\"text\":\"unsent\"}',456)")
            db.version = 2
        }
        try {
            EncryptedDatabase.open(app, LocalVault(app), name).let { db ->
                try {
                    assertTrue(db.cache().hidden("alice", "deleted"))
                    assertFalse(db.cache().hidden("bob", "deleted"))
                    assertEquals(123L, db.cache().localCutoff("alice")!!.throughTime)
                    assertNotNull(db.cache().get("draft", "c"))
                    assertEquals("p", db.cache().pendingItems().single().id)
                    db.cache().clear(); db.cache().clearPending()
                    assertTrue(db.cache().hidden("alice", "deleted"))
                    assertEquals(123L, db.cache().localCutoff("alice")!!.throughTime)
                } finally { db.close() }
            }
        } finally { app.deleteDatabase(name) }
    }
}
