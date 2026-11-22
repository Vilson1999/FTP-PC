package com.example

import android.app.Application
import com.example.data.SettingsRepository
import com.example.ftp.FtpServerEngine
import com.example.storage.VirtualStorageManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class BeamFtpApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var storageManager: VirtualStorageManager
        private set

    lateinit var ftpEngine: FtpServerEngine
        private set

    private val _closeAppRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeAppRequests: SharedFlow<Unit> = _closeAppRequests.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        storageManager = VirtualStorageManager(this)
        ftpEngine = FtpServerEngine(storageManager)
    }

    fun requestCloseApp() {
        _closeAppRequests.tryEmit(Unit)
    }
}
