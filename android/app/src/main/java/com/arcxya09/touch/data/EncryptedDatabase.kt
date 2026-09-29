package com.arcxya09.touch.data

import android.content.Context
import android.system.Os
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.arcxya09.touch.security.LocalVault
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

object EncryptedDatabase {
    val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE items ADD COLUMN conversationId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE items ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE items ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE items ADD COLUMN attachmentId TEXT NOT NULL DEFAULT ''")
            db.query("SELECT kind,id,json FROM items WHERE kind IN ('message','attachment')").use { rows ->
                while (rows.moveToNext()) {
                    val item = TouchDatabase.Item(rows.getString(0), rows.getString(1), rows.getString(2))
                    db.execSQL("UPDATE items SET conversationId=?,seq=?,createdAt=?,attachmentId=? WHERE kind=? AND id=?",
                        arrayOf(item.conversationId, item.seq, item.createdAt, item.attachmentId, item.kind, item.id))
                }
            }
            db.execSQL("CREATE INDEX index_items_kind_conversationId_seq ON items(kind,conversationId,seq)")
            db.execSQL("CREATE INDEX index_items_kind_createdAt ON items(kind,createdAt)")
            db.execSQL("CREATE INDEX index_items_attachmentId ON items(attachmentId)")
            db.execSQL("CREATE INDEX index_outbox_conversationId_createdAt ON outbox(conversationId,createdAt)")
        }
    }
    @Synchronized fun open(context: Context, vault: LocalVault, name: String = "touch.db"): TouchDatabase {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath(name)
        val plain = file.exists() && file.inputStream().use { input ->
            val header = ByteArray(16); input.read(header); header.contentEquals("SQLite format 3\u0000".toByteArray())
        }
        // The ASCII encoding is intentional: the export and Room use the same passphrase.
        val passphrase = vault.secret("database-key", !file.exists() || plain)
            .joinToString("") { "%02x".format(it) }.toByteArray()
        if (plain) encryptExisting(file, passphrase)
        return Room.databaseBuilder(context, TouchDatabase::class.java, name)
            .openHelperFactory { config ->
                val callback = config.callback
                SupportOpenHelperFactory(passphrase).create(SupportSQLiteOpenHelper.Configuration.builder(config.context)
                    .name(config.name).callback(object : SupportSQLiteOpenHelper.Callback(callback.version) {
                        override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(db)
                        override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(db)
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onUpgrade(db, oldVersion, newVersion)
                        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(db, oldVersion, newVersion)
                        override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(db)
                        override fun onCorruption(db: SupportSQLiteDatabase) { error("加密数据库损坏，已保留原文件") }
                    }).build())
            }
            // DELETE journaling limits retention of deleted pages; SQLCipher encrypts journals too.
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(MIGRATION_1_2)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) { db.query("PRAGMA secure_delete=ON").use { check(it.moveToFirst() && it.getInt(0) == 1) } }
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) { error("禁止清空数据库升级") }
            }).build()
    }

    private fun encryptExisting(file: File, passphrase: ByteArray) {
        // The source remains authoritative until a verified, fully closed encrypted copy is renamed.
        val target = File(file.path + ".encrypting")
        check(!target.exists() || target.delete())
        val counts = mutableMapOf<String, Long>()
        var version = 0
        SQLiteDatabase.openDatabase(file.path, byteArrayOf(), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            { _, _ -> throw IllegalStateException("原有数据库损坏，已保留原文件") }, null).use { source ->
            source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { if (it.moveToFirst()) check(it.getInt(0) == 0) }
            source.rawQuery("PRAGMA journal_mode=DELETE", null).close()
            version = source.version
            for (table in listOf("items", "outbox")) source.rawQuery("SELECT count(*) FROM $table", null).use {
                check(it.moveToFirst()); counts[table] = it.getLong(0)
            }
            source.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(target.path, passphrase.toString(Charsets.UTF_8)))
            source.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { check(it.moveToFirst()) }
            source.execSQL("PRAGMA encrypted.user_version=$version")
            source.execSQL("DETACH DATABASE encrypted")
        }
        SQLiteDatabase.openDatabase(target.path, passphrase, null, SQLiteDatabase.OPEN_READONLY,
            { _, _ -> throw IllegalStateException("数据库加密校验失败，已保留原文件") }, null).use { encrypted ->
            check(encrypted.version == version)
            encrypted.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
            for ((table, count) in counts) encrypted.rawQuery("SELECT count(*) FROM $table", null).use {
                check(it.moveToFirst() && it.getLong(0) == count)
            }
        }
        Os.rename(target.path, file.path)
    }
}
