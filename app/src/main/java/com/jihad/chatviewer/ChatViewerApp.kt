package com.jihad.chatviewer

import android.app.Application
import com.jihad.chatviewer.data.AppDatabase
import com.jihad.chatviewer.importer.ScanResult

class AppContainer(app: Application) {
    val database: AppDatabase by lazy { AppDatabase.create(app) }

    @Volatile
    var lastScan: ScanResult? = null
}

class ChatViewerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
