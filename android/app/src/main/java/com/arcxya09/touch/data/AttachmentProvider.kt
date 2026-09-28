package com.arcxya09.touch.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Only explicit, short-lived URI grants can read decrypted bytes. Never writes plaintext to disk. */
class AttachmentProvider : ContentProvider() {
    companion object {
        private val grants = ConcurrentHashMap<String, Pair<EncryptedAttachment, Long>>()
        fun share(authority: String, file: EncryptedAttachment): Uri {
            file.checkAccess()
            val now = SystemClock.elapsedRealtime()
            grants.entries.removeIf { it.value.second <= now || !it.value.first.valid() }
            val token = UUID.randomUUID().toString()
            grants[token] = file to (now + 10 * 60 * 1000)
            return Uri.Builder().scheme("content").authority(authority).appendPath(token).build()
        }
    }
    private fun file(uri: Uri): EncryptedAttachment {
        val grant = grants[uri.lastPathSegment] ?: throw FileNotFoundException()
        if (grant.second <= SystemClock.elapsedRealtime() || !grant.first.valid()) {
            grants.remove(uri.lastPathSegment); throw FileNotFoundException()
        }
        return grant.first
    }
    override fun onCreate() = true
    override fun getType(uri: Uri) = file(uri).item.mime
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        val item = file(uri).item
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) item.size else item.name }) }
    }
    override fun openFile(uri: Uri, mode: String) = if (mode == "r") file(uri).descriptor() else throw FileNotFoundException()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
