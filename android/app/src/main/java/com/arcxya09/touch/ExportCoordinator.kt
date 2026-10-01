package com.arcxya09.touch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.arcxya09.touch.data.ChatMessage
import com.arcxya09.touch.data.EncryptedAttachment
import com.arcxya09.touch.data.Repository
import com.arcxya09.touch.security.LocalVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns one explicit export across picker/activity recreation; never stages decrypted content. */
internal class ExportCoordinator(context: Context, private val repository: Repository, vault: LocalVault) {
    private val resolver = context.applicationContext.contentResolver
    private val store = PendingExportStore(vault)
    private val lock = Mutex()

    suspend fun prepare(file: EncryptedAttachment, message: ChatMessage, owner: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            file.checkAccess()
            check(file.owner == owner && repository.api.user?.id == owner) { "账号已改变，请重新打开附件" }
            store.write(PendingExport(owner, message.conversationId, message.id, System.currentTimeMillis() + 30 * 60 * 1000L))
        }
    }

    suspend fun recoverInterrupted(): String? = lock.withLock { withContext(Dispatchers.IO) {
        val request = store.read() ?: return@withContext null
        val destination = request.destinationUri?.let(Uri::parse) ?: return@withContext null
        val removed = removeIncomplete(destination)
        store.clear(); release(destination)
        if (removed) "上次文件保存未完成，请重新选择保存位置"
        else "上次文件保存未完成，请删除原位置的未完成文件后重新保存"
    } }

    /** Returns false for picker cancellation. All copy and cleanup work stays off the main thread. */
    suspend fun finish(destination: Uri?): Boolean = lock.withLock {
        try {
            withContext(Dispatchers.IO) {
                if (destination == null) return@withContext false
                repository.initialize()
                val request = store.read() ?: error("保存请求已失效，请重新选择附件")
                check(request.validFor(repository.api.user?.id, System.currentTimeMillis())) { "账号已改变或保存请求已过期" }
                runCatching { resolver.takePersistableUriPermission(destination, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                store.write(request.copy(destinationUri = destination.toString()))
                val file = repository.attachmentForExport(request.owner, request.conversationId, request.messageId)
                resolver.openOutputStream(destination, "wt")?.use { output ->
                    file.input().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(repository.api.user?.id == request.owner) { "账号已改变，保存已停止" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("无法写入所选位置")
                // Do not report success while crash recovery would still classify this destination as incomplete.
                store.clear()
                true
            }
        } catch (error: Exception) {
            val removed = withContext(NonCancellable + Dispatchers.IO) { destination == null || removeIncomplete(destination) }
            if (error is CancellationException) throw error
            throw IllegalStateException(if (removed) "文件保存失败，请重新选择附件和保存位置"
                else "文件保存失败，请删除原位置的未完成文件后重试", error)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { store.clear() }
                destination?.let(::release)
            }
        }
    }

    private fun removeIncomplete(uri: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    private fun release(uri: Uri) { runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
}
