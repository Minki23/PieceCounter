package com.piececounter.app

import android.app.Application
import com.piececounter.app.data.local.AppDatabase
import com.piececounter.app.data.repository.SessionRepository

class PieceCounterApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: SessionRepository by lazy { SessionRepository(database) }
}
