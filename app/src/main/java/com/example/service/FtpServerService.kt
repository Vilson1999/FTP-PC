package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.BeamFtpApplication
import com.example.MainActivity
import com.example.R
import com.example.data.AppSettings
import com.example.ftp.FtpEngineTelemetry
import com.example.ftp.FtpServerEngine
import com.example.ftp.ServerRunState
import com.example.network.NetworkAddressDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service (dataSync) that manages the Apache MINA FTP server.
 * Immediately stops itself and releases all locks whenever FTP is stopped.
 */
class FtpServerService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START_FTP"
        const val ACTION_STOP = "com.example.action.STOP_FTP"
        const val ACTION_STOP_FROM_NOTIFICATION = "com.example.action.STOP_FROM_NOTIFICATION"
        private const val CHANNEL_ID = "beamftp_server_channel"
        private const val NOTIFICATION_ID = 4021

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun startServer(context: Context) {
            val intent = Intent(context, FtpServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopServer(context: Context) {
            if (!isServiceRunning) return
            val intent = Intent(context, FtpServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var ftpEngine: FtpServerEngine
    private var settingsJob: Job? = null
    private var telemetryJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastAppliedSettings: AppSettings? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        ftpEngine = (application as BeamFtpApplication).ftpEngine
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BeamFtpApplication
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngineAndService(closeApp = false)
                return START_NOT_STICKY
            }
            ACTION_STOP_FROM_NOTIFICATION -> {
                stopEngineAndService(closeApp = true)
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                promoteToForeground("Initializing FTP PC server...", 0, readOnly = false)
                serviceScope.launch {
                    val settings = app.settingsRepository.settingsFlow.first()
                    startEngineWithSettings(app, settings)
                }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startEngineWithSettings(app: BeamFtpApplication, initialSettings: AppSettings) {
        lastAppliedSettings = initialSettings
        syncPowerLocks(initialSettings)
        ftpEngine.start(initialSettings)
        observeSettingsChanges(app)
        observeTelemetry(app)
    }

    private fun observeSettingsChanges(app: BeamFtpApplication) {
        if (settingsJob?.isActive == true) return
        settingsJob = serviceScope.launch {
            app.settingsRepository.settingsFlow.collect { latest ->
                val previous = lastAppliedSettings
                lastAppliedSettings = latest

                ftpEngine.updateSettings(latest)
                syncPowerLocks(latest)

                if (previous != null &&
                    previous.port != latest.port &&
                    ftpEngine.telemetry.value.runState == ServerRunState.RUNNING
                ) {
                    withContext(Dispatchers.IO) { ftpEngine.stop() }
                    ftpEngine.start(latest)
                }

                updateNotificationFromState(latest, ftpEngine.telemetry.value)
            }
        }
    }

    private fun observeTelemetry(app: BeamFtpApplication) {
        if (telemetryJob?.isActive == true) return
        telemetryJob = serviceScope.launch {
            ftpEngine.telemetry.collect { state ->
                if (state.runState == ServerRunState.STOPPED) {
                    stopForegroundAndRelease()
                    getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                    stopSelf()
                    return@collect
                }
                val settings = lastAppliedSettings ?: app.settingsRepository.settingsFlow.first()
                updateNotificationFromState(settings, state)
            }
        }
    }

    private fun updateNotificationFromState(settings: AppSettings, state: FtpEngineTelemetry) {
        val statusText = when (state.runState) {
            ServerRunState.STARTING -> "Starting FTP server on port ${settings.port}..."
            ServerRunState.ERROR -> state.errorMessage ?: "FTP server error on port ${settings.port}"
            ServerRunState.STOPPED -> return
            ServerRunState.RUNNING -> {
                val endpoints = NetworkAddressDetector.detectEndpoints(preferHotspot = settings.preferHotspotIp)
                endpoints.firstOrNull()
                    ?.toFtpUrl(state.boundPort, settings.username, settings.allowAnonymous)
                    ?: "Listening on port ${state.boundPort}"
            }
        }
        val notif = buildNotification(
            urlText = statusText,
            activeConnections = state.activeConnections,
            readOnly = settings.readOnly
        )
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notif)
    }

    private fun promoteToForeground(statusText: String, activeConnections: Int, readOnly: Boolean) {
        val notification = buildNotification(statusText, activeConnections, readOnly)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun syncPowerLocks(settings: AppSettings) {
        if (settings.runInBackground && settings.keepWakeLock) {
            acquireLocks()
        } else {
            releaseLocks()
        }
    }

    private fun stopEngineAndService(closeApp: Boolean) {
        settingsJob?.cancel()
        settingsJob = null
        telemetryJob?.cancel()
        telemetryJob = null

        serviceScope.launch {
            withContext(Dispatchers.IO) {
                ftpEngine.stop()
            }
            stopForegroundAndRelease()
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            if (closeApp) {
                (application as BeamFtpApplication).requestCloseApp()
            }
            stopSelf()
        }
    }

    private fun stopForegroundAndRelease() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FTPPC::ServerWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L)
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "FTPPC::WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        urlText: String,
        activeConnections: Int,
        readOnly: Boolean
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FtpServerService::class.java).apply {
                action = ACTION_STOP_FROM_NOTIFICATION
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clientSummary = if (activeConnections == 1) "1 PC connected" else "$activeConnections PCs connected"
        val modeTag = if (readOnly) "Read-Only" else "Read/Write"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FTP PC ($modeTag) • $clientSummary")
            .setContentText(urlText)
            .setSmallIcon(R.drawable.ic_stat_ftp)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        isServiceRunning = false
        settingsJob?.cancel()
        telemetryJob?.cancel()
        if (::ftpEngine.isInitialized && ftpEngine.telemetry.value.runState != ServerRunState.STOPPED) {
            ftpEngine.stop()
        }
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }
}
