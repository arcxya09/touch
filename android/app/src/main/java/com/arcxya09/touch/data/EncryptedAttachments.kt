package com.arcxya09.touch.data

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.arcxya09.touch.security.LocalVault
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class EncryptedAttachments(private val context: Context, vault: LocalVault) {
    val folder = File(context.cacheDir, "sealed-attachments").apply { mkdirs() }
    private val aead: StreamingAead by lazy {
        StreamingAeadConfig.register()
        val bytes = vault.secret("attachments-key", folder.listFiles().isNullOrEmpty()) {
            val handle = KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES128_GCM_HKDF_4KB)
            ByteArrayOutputStream().also { CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(it)) }.toByteArray()
        }
        CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(bytes)).getPrimitive(StreamingAead::class.java)
    }
    fun file(item: FileItem, owner: String, createdAt: Long, allowed: () -> Boolean): EncryptedAttachment {
        require(item.id.matches(Regex("[a-fA-F0-9-]{36}")))
        // Initialize the key before the first partial file exists.
        return EncryptedAttachment(context, aead, File(folder, item.id), item, owner, createdAt, allowed)
    }
}

class EncryptedAttachment internal constructor(
    private val context: Context, private val aead: StreamingAead, val encryptedFile: File,
    val item: FileItem, val owner: String, val createdAt: Long, private val allowed: () -> Boolean
) {
    private val aad = "touch-attachment-v1:$owner:${item.id}".toByteArray()
    fun checkAccess() { check(allowed()) { "文件已超过本机保留时间或账号已退出" } }
    fun valid() = runCatching { checkAccess(); encryptedFile.exists() }.getOrDefault(false)
    fun encryptTo(file: File) = aead.newEncryptingStream(file.outputStream(), aad)
    fun input(): InputStream {
        checkAccess()
        val raw = encryptedFile.inputStream()
        val decrypted = try { aead.newDecryptingStream(raw, aad) } catch (e: Exception) { raw.close(); throw e }
        return object : FilterInputStream(decrypted) {
            override fun read(): Int { checkAccess(); return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { checkAccess(); return `in`.read(b, off, len) }
            override fun skip(n: Long): Long { checkAccess(); return super.skip(n) }
        }
    }
    /** Seekable decryption for PdfRenderer and explicitly authorized external viewers, with no plaintext temp file. */
    fun descriptor(): ParcelFileDescriptor {
        checkAccess()
        val raw = FileChannel.open(encryptedFile.toPath(), StandardOpenOption.READ)
        val channel = try { aead.newSeekableDecryptingChannel(raw, aad) } catch (e: Exception) { raw.close(); throw e }
        val thread = HandlerThread("attachment-reader").apply { start() }
        try {
            // Authenticate the first segment so Tink selects the matching key before size()/seek().
            channel.read(ByteBuffer.allocate(1))
            check(channel.size() == item.size) { "文件大小校验失败" }
            channel.position(0)
            return context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = guarded { item.size }
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = guarded {
                        channel.position(offset)
                        val buffer = ByteBuffer.wrap(data, 0, size)
                        var read = 0
                        while (buffer.hasRemaining()) {
                            val count = channel.read(buffer)
                            if (count < 0) break
                            read += count
                        }
                        read
                    }
                    private fun <T> guarded(block: () -> T): T = try { checkAccess(); block() }
                        catch (_: Exception) { throw ErrnoException("encrypted attachment", OsConstants.EIO) }
                    override fun onRelease() { try { channel.close() } finally { thread.quitSafely() } }
                }, Handler(thread.looper))
        } catch (e: Exception) { channel.close(); thread.quitSafely(); throw e }
    }
}
