package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BeamFtpApplication
import com.example.data.AppSettings
import com.example.ftp.FtpEngineTelemetry
import com.example.ftp.ServerRunState
import com.example.network.NetworkAddressDetector
import com.example.network.NetworkEndpoint
import com.example.service.FtpServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ServerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BeamFtpApplication
    private val settingsRepo = app.settingsRepository
    private val engine = app.ftpEngine

    val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val telemetry: StateFlow<FtpEngineTelemetry> = engine.telemetry

    private val _endpoints = MutableStateFlow<List<NetworkEndpoint>>(emptyList())
    val endpoints: StateFlow<List<NetworkEndpoint>> = _endpoints.asStateFlow()

    private val isUiInForeground = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { latest ->
                engine.updateSettings(latest)
                refreshNetwork(latest.preferHotspotIp)
            }
        }

        // Only poll network interfaces while UI is in the foreground
        viewModelScope.launch {
            isUiInForeground.collectLatest { foreground ->
                if (!foreground) return@collectLatest
                while (isActive) {
                    refreshNetwork(settings.value.preferHotspotIp)
                    delay(4000L)
                }
            }
        }
    }

    fun setUiForeground(inForeground: Boolean) {
        isUiInForeground.value = inForeground
    }

    fun refreshNetwork(preferHotspot: Boolean = settings.value.preferHotspotIp) {
        viewModelScope.launch(Dispatchers.IO) {
            _endpoints.value = NetworkAddressDetector.detectEndpoints(preferHotspot)
        }
    }

    fun selectFtpSlot(slot: Int) {
        viewModelScope.launch {
            settingsRepo.setSelectedFtpSlot(slot)
        }
    }

    fun toggleServer() {
        when (telemetry.value.runState) {
            ServerRunState.RUNNING, ServerRunState.STARTING -> {
                FtpServerService.stopServer(app)
                engine.stop()
            }
            ServerRunState.STOPPED, ServerRunState.ERROR -> {
                FtpServerService.startServer(app)
            }
        }
    }

    fun onAppBackgrounded() {
        val state = telemetry.value.runState
        val isFtpActive = state == ServerRunState.RUNNING || state == ServerRunState.STARTING
        if (!isFtpActive || !settings.value.runInBackground) {
            FtpServerService.stopServer(app)
            if (isFtpActive) {
                engine.stop()
            }
        }
    }

    fun createSlotStoragePickerIntent(slot: Int): Intent {
        val clamped = slot.coerceIn(1, 4)
        val sm = app.getSystemService(StorageManager::class.java)
        val existingUri = settings.value.storageForSlot(clamped).uriString?.let {
            runCatching { Uri.parse(it) }.getOrNull()
        }

        if (existingUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (clamped) {
                1 -> sm?.primaryStorageVolume?.createOpenDocumentTreeIntent()?.let { return it }
                2 -> sm?.storageVolumes?.firstOrNull { it.isRemovable }
                    ?.createOpenDocumentTreeIntent()?.let { return it }
            }
        }

        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val initialUri = existingUri ?: when (clamped) {
                    1 -> DocumentsContract.buildTreeDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:"
                    )
                    3 -> DocumentsContract.buildTreeDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Download"
                    )
                    else -> null
                }
                if (initialUri != null) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                }
            }
        }
    }

    fun onSlotStorageUriPicked(slot: Int, uri: Uri, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch {
            takePersistableTreePermission(uri)
            val label = settingsRepo.setSlotStorageUri(slot, uri)
            onSaved(label)
        }
    }

    fun setRunInBackground(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setRunInBackground(enabled) }
    }

    private fun takePersistableTreePermission(uri: Uri) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            app.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }
}
