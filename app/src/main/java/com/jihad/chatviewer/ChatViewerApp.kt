package com.jihad.chatviewer

import android.app.Application
import com.jihad.chatviewer.data.AppDatabase
import com.jihad.chatviewer.importer.ImportSource
import com.jihad.chatviewer.importer.ImportUiState
import com.jihad.chatviewer.importer.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow

class AppContainer(app: Application) {
    val database: AppDatabase by lazy { AppDatabase.create(app) }

    @Volatile
    var lastScan: ScanResult? = null

    @Volatile
    var lastSource: ImportSource? = null

    @Volatile
    var selectedChatIds: Set<String> = emptySet()

    val importState = MutableStateFlow(ImportUiState())
}

class ChatViewerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
