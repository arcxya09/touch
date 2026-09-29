package com.arcxya09.touch

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.data.*
import com.arcxya09.touch.security.LocalVault
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class LocalStorageInstrumentedTest {
    private inline fun <T> TouchDatabase.use(block: (TouchDatabase) -> T): T = try { block(this) } finally { close() }
    private inline fun <T> PdfDocument.use(block: (PdfDocument) -> T): T = try { block(this) } finally { close() }
    private val app get() = ApplicationProvider.getApplicationContext<TouchApp>()
    private fun vault(): LocalVault = LocalVault(object : ContextWrapper(app) {
        private val dir = File(app.noBackupFilesDir, "test-" + UUID.randomUUID()).apply { mkdirs() }
        override fun getNoBackupFilesDir() = dir
    })
    @Test fun plaintextDatabaseMigratesAtomicallyAndReopensWithAllRows() {
        val name = "migration-${UUID.randomUUID()}.db"
        val vault = vault()
        val secret = "private-upgrade-canary-" + UUID.randomUUID()
        val original = Room.databaseBuilder(app, TouchDatabase::class.java, name).build()
        original.cache().put(TouchDatabase.Item("message", "m", JSONObject().put("text", secret).toString()))
        original.cache().put(TouchDatabase.Item("meta", "cursor", "87"))
        original.cache().pending(TouchDatabase.Outbox("p", "c", secret, 123))
        original.close()
        assertTrue(app.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        EncryptedDatabase.open(app, vault, name).use { db ->
            assertEquals(secret, JSONObject(db.cache().get("message", "m")!!.json).getString("text"))
            assertEquals("87", db.cache().get("meta", "cursor")!!.json)
            assertEquals(secret, db.cache().pendingItems().single().body)
        }
        val encrypted = app.getDatabasePath(name).readBytes()
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(secret))
        assertFalse(encrypted.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
        EncryptedDatabase.open(app, vault, name).use { assertEquals(secret, JSONObject(it.cache().get("message", "m")!!.json).getString("text")) }
        assertFalse(File(app.getDatabasePath(name).path + ".encrypting").exists())
        // A lost or incorrect key cannot trigger Room's automatic corruption deletion.
        assertTrue(runCatching { EncryptedDatabase.open(app, vault(), name).use { it.cache().items("message") } }.isFailure)
        vault.write("database-key", ByteArray(32) { 1 })
        assertTrue(runCatching { EncryptedDatabase.open(app, vault, name).use { it.cache().items("message") } }.isFailure)
        assertTrue(app.getDatabasePath(name).exists())
        assertArrayEquals(encrypted, app.getDatabasePath(name).readBytes())
        app.deleteDatabase(name)
    }

    @Test fun streamingEncryptionSupportsImagesPdfSeekingAndRejectsTamperingAndExpiredHandles() {
        val files = EncryptedAttachments(app, app.vault)
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val item = FileItem(UUID.randomUUID().toString(), "private.txt", "text/plain", "file", bytes.size.toLong(), hash(bytes))
        var allowed = true
        val file = files.file(item, "test-owner", 1) { allowed }
        file.encryptTo(file.encryptedFile).use { it.write(bytes) }
        assertFalse(file.encryptedFile.readBytes().contentEquals(bytes))
        assertArrayEquals(bytes, file.input().use { it.readBytes() })
        file.descriptor().use { pfd ->
            val slice = ByteArray(8000)
            assertEquals(8000, android.system.Os.pread(pfd.fileDescriptor, slice, 0, 8000, 3500))
            assertArrayEquals(bytes.copyOfRange(3500, 11500), slice)
            allowed = false
            // FUSE may cache already delivered pages, just as external viewers may retain copies.
            // Reading a not-yet-delivered region must consult the expiry guard.
            assertTrue(runCatching { android.system.Os.pread(pfd.fileDescriptor, slice, 0, 1, 900000) }.isFailure)
        }
        assertTrue(runCatching { file.input() }.isFailure)
        allowed = true
        val differentOwner = files.file(item, "wrong-owner", 1) { true }
        assertTrue(runCatching { differentOwner.input().use { it.readBytes() } }.isFailure)
        val cipher = file.encryptedFile.readBytes(); cipher[cipher.lastIndex] = (cipher.last().toInt() xor 1).toByte()
        file.encryptedFile.writeBytes(cipher)
        assertTrue(runCatching { file.input().use { it.readBytes() } }.isFailure)
        file.encryptedFile.delete()
        val png = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }.toByteArray()
        val image = files.file(item.copy(id = UUID.randomUUID().toString(), size = png.size.toLong()), "test-owner", 1) { true }
        image.encryptTo(image.encryptedFile).use { it.write(png) }
        image.input().use { assertEquals(32, BitmapFactory.decodeStream(it)!!.width) }
        image.encryptedFile.delete()
        val pdf = ByteArrayOutputStream().also { output ->
            PdfDocument().use { document ->
                repeat(2) { page -> document.finishPage(document.startPage(PdfDocument.PageInfo.Builder(100, 100, page).create())) }
                document.writeTo(output)
            }
        }.toByteArray()
        val document = files.file(item.copy(id = UUID.randomUUID().toString(), size = pdf.size.toLong()), "test-owner", 1) { true }
        document.encryptTo(document.encryptedFile).use { it.write(pdf) }
        PdfRenderer(document.descriptor()).use { renderer ->
            assertEquals(2, renderer.pageCount)
            renderer.openPage(1).use { assertEquals(100, it.width) }
        }
        document.encryptedFile.delete()
    }

    @Test fun purgeIncludesOutboxAndFilesAndNeverRestoresHistoryAfterLogoutOrLongerWindow() = runBlocking<Unit> {
        val repo = app.repository
        repo.logout(false)
        val cache = app.database.cache()
        val owner = UUID.randomUUID().toString()
        val now = System.currentTimeMillis() / 1000
        cache.put(TouchDatabase.Item("meta", "owner", owner))
        repo.purge()
        repo.enableRetention(true)
        repo.configureRetention(3600)
        val fileId = UUID.randomUUID().toString()
        fun message(id: String, time: Long) = JSONObject().put("id", id).put("conversation_id", "c").put("sender_id", owner)
            .put("client_id", id).put("seq", 1).put("kind", "file").put("text", "private-retention-canary")
            .put("created_at", time).put("attachment", JSONObject().put("id", fileId).put("name", "secret.txt")
                .put("mime", "text/plain").put("kind", "file").put("size", 6).put("sha256", hash("secret".toByteArray())))
        val old = message("old", now - 7200)
        val recent = message("new", now).put("attachment", JSONObject.NULL).put("kind", "text")
        cache.put(TouchDatabase.Item("message", "old", old.toString()))
        cache.put(TouchDatabase.Item("message", "new", recent.toString()))
        cache.pending(TouchDatabase.Outbox("old-outbox", "c", "{\"text\":\"private-unsent\"}", now - 7200))
        val encrypted = EncryptedAttachments(app, app.vault).file(ChatMessage.parse(old).file!!, owner, now - 7200) { true }
        encrypted.encryptTo(encrypted.encryptedFile).use { it.write("secret".toByteArray()) }
        assertTrue(repo.purge())
        assertNull(cache.get("message", "old"))
        assertNotNull(cache.get("message", "new"))
        assertTrue(cache.pendingItems().isEmpty())
        assertFalse(encrypted.encryptedFile.exists())
        val floor = repo.retention.cutoff(owner)
        repo.configureRetention(30 * 86400)
        assertTrue(repo.retention.cutoff(owner) >= floor)
        assertTrue(runCatching { repo.download(ChatMessage.parse(old)) { } }.exceptionOrNull()?.message?.contains("保留时间") == true)
        repo.logout(false)
        assertTrue(RetentionPolicy(app.vault).cutoff(owner) >= floor)
        cache.put(TouchDatabase.Item("meta", "owner", owner)); repo.purge()
        assertTrue(runCatching { repo.download(ChatMessage.parse(old)) { } }.isFailure)
        repo.logout(false)
        repo.enableRetention(false)
    }

    @Test fun policySurvivesClockRollbackAndFailsClosedOnTampering() {
        val vault = vault()
        var now = 1000000L
        val policy = RetentionPolicy(vault) { now }
        policy.setEnabled(true, "alice")
        policy.configure(3600, "alice")
        val floor = policy.cutoff("alice")
        now -= 5000
        assertEquals(floor, policy.cutoff("alice"))
        policy.configure(86400, "alice")
        assertEquals(floor, RetentionPolicy(vault) { now }.cutoff("alice"))
        vault.write("retention", "{\"bad\":true}".toByteArray())
        assertTrue(runCatching { RetentionPolicy(vault).cutoff("alice") }.isFailure)
    }
    @Test fun legacyPlaintextAttachmentIsEncryptedBeforeAccessAndExpiredCopyIsDeleted() = runBlocking<Unit> {
        val repo = app.repository
        repo.logout(false)
        val cache = app.database.cache()
        val owner = UUID.randomUUID().toString()
        cache.put(TouchDatabase.Item("meta", "owner", owner))
        repo.purge()
        repo.enableRetention(true)
        repo.configureRetention(3600)
        val id = UUID.randomUUID().toString()
        val bytes = "legacy-file-private-canary".toByteArray()
        val now = System.currentTimeMillis() / 1000
        val json = JSONObject().put("id", "legacy").put("conversation_id", "c").put("sender_id", owner)
            .put("client_id", "legacy").put("seq", 1).put("kind", "file").put("text", "").put("created_at", now)
            .put("attachment", JSONObject().put("id", id).put("name", "legacy.txt").put("kind", "file")
                .put("mime", "text/plain").put("size", bytes.size).put("sha256", hash(bytes)))
        cache.put(TouchDatabase.Item("message", "legacy", json.toString()))
        val folder = File(app.cacheDir, "attachments").apply { mkdirs() }
        val plain = File(folder, id).apply { writeBytes(bytes) }
        val orphan = File(folder, "expired-or-unknown").apply { writeBytes(bytes) }
        repo.purge()
        assertFalse(plain.exists()); assertFalse(orphan.exists())
        val migrated = repo.download(ChatMessage.parse(json)) { }
        assertArrayEquals(bytes, migrated.input().use { it.readBytes() })
        assertFalse(migrated.encryptedFile.readBytes().toString(Charsets.ISO_8859_1).contains("legacy-file-private-canary"))
        repo.logout(false)
        assertFalse(migrated.valid())
        repo.enableRetention(false)
    }
    @Test fun retentionIsOffByDefaultAndDisablingFreezesFloorAcrossRestart() {
        val vault = vault()
        var now = 1000000L
        val policy = RetentionPolicy(vault) { now }
        assertFalse(policy.enabled)
        assertEquals(3600L, policy.seconds)
        assertEquals(0L, policy.cutoff("alice"))
        now += 86400
        assertEquals(0L, policy.cutoff("alice"))
        policy.setEnabled(true, "alice")
        assertEquals(now - 3600, policy.cutoff("alice"))
        policy.setEnabled(false, "alice")
        val floor = policy.cutoff("alice")
        now += 86400
        assertEquals(floor, RetentionPolicy(vault) { now }.cutoff("alice"))
        policy.configure(86400, "alice")
        policy.setEnabled(true, "alice")
        assertEquals(3600L, policy.seconds)
        assertTrue(policy.cutoff("alice") > floor)
    }
    @Test fun upgradeTurnsOldAutomaticRetentionOffWithoutLosingDestroyedFloor() {
        val vault = vault()
        vault.write("retention", JSONObject().put("schema", 1).put("seconds", 604800)
            .put("floors", JSONObject().put("alice", 100000)).toString().toByteArray())
        val policy = RetentionPolicy(vault) { 9999999L }
        assertFalse(policy.enabled)
        assertEquals(3600L, policy.seconds)
        assertEquals(100000L, policy.cutoff("alice"))
        assertEquals(0L, policy.cutoff("bob"))
        assertFalse(RetentionPolicy(vault).enabled)
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
