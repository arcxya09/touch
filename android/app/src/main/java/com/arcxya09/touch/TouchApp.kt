package com.arcxya09.touch

import android.app.Application
import com.arcxya09.touch.data.EncryptedDatabase
import com.arcxya09.touch.data.RetentionPolicy
import com.arcxya09.touch.data.LocalCleanupJob
import com.arcxya09.touch.security.LocalVault
import com.arcxya09.touch.data.TouchDatabase
import com.arcxya09.touch.data.Repository
import com.arcxya09.touch.security.SecureStore

class TouchApp : Application() {
    val secureStore by lazy { SecureStore(this) }
    val vault by lazy { LocalVault(this) }
    val retention by lazy { RetentionPolicy(vault) }
    val database by lazy { EncryptedDatabase.open(this, vault) }
    val repository by lazy { Repository(this, { database }, secureStore) }
    override fun onCreate() { super.onCreate(); LocalCleanupJob.schedule(this) }
}
