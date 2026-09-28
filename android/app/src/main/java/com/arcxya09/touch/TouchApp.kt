package com.arcxya09.touch

import android.app.Application
import androidx.room.Room
import com.arcxya09.touch.data.TouchDatabase
import com.arcxya09.touch.data.Repository
import com.arcxya09.touch.security.SecureStore

class TouchApp : Application() {
    val secureStore by lazy { SecureStore(this) }
    val database by lazy {
        Room.databaseBuilder(this, TouchDatabase::class.java, "touch.db")
            // Future schema changes MUST add explicit migrations; never use destructive fallback.
            .build()
    }
    val repository by lazy { Repository(this, database, secureStore) }
}
