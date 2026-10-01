package com.arcxya09.touch.data

import java.io.File

/** Enqueue inside the deleting transaction; acknowledge only after the physical file is gone. */
class AttachmentCleanup(
    private val cache: () -> TouchDatabase.Cache,
    private val folder: () -> File,
    private val remove: (File) -> Boolean = File::delete,
) {
    fun enqueue(id: String) {
        require(id.isNotBlank() && id != "." && id != ".." && '/' !in id && '\\' !in id)
        cache().deleteFile(TouchDatabase.FileDeletion(id))
    }

    fun enqueueAll() { folder().listFiles()?.filter(File::isFile)?.forEach { enqueue(it.name) } }

    fun drain(): Boolean {
        var failed = false
        do {
            val batch = cache().fileDeletions()
            batch.forEach { entry ->
                val root = folder().canonicalFile
                val file = File(root, entry.id).canonicalFile
                check(file.parentFile == root) { "附件清理路径无效" }
                if (!file.exists() || remove(file)) cache().fileDeleted(entry.id) else failed = true
            }
        } while (batch.size == 100 && !failed)
        return !failed
    }
}
