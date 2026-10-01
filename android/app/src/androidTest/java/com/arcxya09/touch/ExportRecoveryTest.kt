package com.arcxya09.touch

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.arcxya09.touch.security.LocalVault
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ExportRecoveryTest {
    @Test fun authorizedRequestAndInterruptedDestinationSurviveRecreationOnlyInsideEncryptedVault() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(app.cacheDir, "export-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = folder }
        try {
            val request = PendingExport("private-owner", "private-conversation", "private-message", System.currentTimeMillis() + 10000)
            PendingExportStore(LocalVault(context)).write(request)
            val restored = PendingExportStore(LocalVault(context))
            assertEquals(request, restored.read())
            val interrupted = request.copy(destinationUri = "content://test-provider/private-document")
            restored.write(interrupted)
            assertEquals(interrupted, PendingExportStore(LocalVault(context)).read())
            val disk = File(folder, "vault/pending-export").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(disk.contains(request.owner))
            assertFalse(disk.contains(request.messageId))
            assertFalse(disk.contains("private-document"))
            restored.clear()
            assertNull(PendingExportStore(LocalVault(context)).read())
        } finally {
            folder.deleteRecursively()
        }
    }
}
