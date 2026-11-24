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
 * Foreground Service (dataSync) that manages up to 4 concurrent Apache MINA FTP slot servers.
 * Immediately stops itself and releases all locks whenever no FTP slot remains active.
 */
class FtpServerService : Service() {

    companion object {
        const val ACTION_START_SLOT = "com.example.action.START_FTP_SLOT"
        const val ACTION_STOP_SLOT = "com.example.action.STOP_FTP_SLOT"
        const val ACTION_STOP_ALL = "com.example.action.STOP_ALL_FTP"
        const val ACTION_STOP_FROM_NOTIFICATION = "com.example.action.STOP_FROM_NOTIFICATION"
        const val EXTRA_SLOT = "extra_ftp_slot"
        private const val CHANNEL_ID = "beamftp_server_channel"
        private const val NOTIFICATION_ID = 4021

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun startSlot(context: Context, slot: Int) {
            val intent = Intent(context, FtpServerService::class.java).apply {
                action = ACTION_START_SLOT
                putExtra(EXTRA_SLOT, slot.coerceIn(1, 4))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopSlot(context: Context, slot: Int) {
            if (!isServiceRunning) return
            val intent = Intent(context, FtpServerService::class.java).apply {
                action = ACTION_STOP_SLOT
                putExtra(EXTRA_SLOT, slot.coerceIn(1, 4))
            }
            context.startService(intent)
        }

        fun stopAll(context: Context) {
            if (!isServiceRunning) return
            val intent = Intent(context, FtpServerService::class.java).apply {
                action = ACTION_STOP_ALL
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
            ACTION_STOP_ALL -> {
                stopEngineAndService(closeApp = false)
                return START_NOT_STICKY
            }
            ACTION_STOP_FROM_NOTIFICATION -> {
                stopEngineAndService(closeApp = true)
                return START_NOT_STICKY
            }
            ACTION_STOP_SLOT -> {
                val slot = intent.getIntExtra(EXTRA_SLOT, 1).coerceIn(1, 4)
                serviceScope.launch {
                    withContext(Dispatchers.IO) {
                        ftpEngine.stopSlot(slot)
                    }
                    if (!ftpEngine.hasAnyActiveSlot()) {
                        stopForegroundAndRelease()
                        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                        stopSelf()
                    } else {
                        val settings = lastAppliedSettings ?: app.settingsRepository.settingsFlow.first()
                        updateNotificationFromSlots(settings, ftpEngine.slotTelemetries.value)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START_SLOT, null -> {
                val requestedSlot = intent?.getIntExtra(EXTRA_SLOT, -1) ?: -1
                if (!ftpEngine.hasAnyActiveSlot()) {
                    promoteToForeground(
                        title = "FTP PC • Starting...",
                        urlText = "Initializing FTP server...",
                        readOnly = false
                    )
                }
                serviceScope.launch {
                    val settings = app.settingsRepository.settingsFlow.first()
                    val slot = if (requestedSlot in 1..4) requestedSlot else settings.selectedFtpSlot.coerceIn(1, 4)
                    lastAppliedSettings = settings
                    syncPowerLocks(settings)
                    ftpEngine.startSlot(slot, settings)
                    observeSettingsChanges(app)
                    observeTelemetry(app)
                }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun observeSettingsChanges(app: BeamFtpApplication) {
        if (settingsJob?.isActive == true) return
        settingsJob = serviceScope.launch {
            app.settingsRepository.settingsFlow.collect { latest ->
                lastAppliedSettings = latest
                ftpEngine.updateSettings(latest)
                syncPowerLocks(latest)
                updateNotificationFromSlots(latest, ftpEngine.slotTelemetries.value)
            }
        }
    }

    private fun observeTelemetry(app: BeamFtpApplication) {
        if (telemetryJob?.isActive == true) return
        telemetryJob = serviceScope.launch {
            ftpEngine.slotTelemetries.collect { slotsMap ->
                if (!ftpEngine.hasAnyActiveSlot()) {
                    stopForegroundAndRelease()
                    getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                    stopSelf()
                    return@collect
                }
                val settings = lastAppliedSettings ?: app.settingsRepository.settingsFlow.first()
                updateNotificationFromSlots(settings, slotsMap)
            }
        }
    }

    private fun updateNotificationFromSlots(
        settings: AppSettings,
        slotsMap: Map<Int, FtpEngineTelemetry>
    ) {
        val activeSlots = slotsMap.entries
            .filter { it.value.runState == ServerRunState.RUNNING || it.value.runState == ServerRunState.STARTING }
            .sortedBy { it.key }
        if (activeSlots.isEmpty()) return

        val runningSlots = activeSlots.filter { it.value.runState == ServerRunState.RUNNING }
        val totalConnections = activeSlots.sumOf { it.value.activeConnections }
        val clientSummary = if (totalConnections == 1) "1 PC connected" else "$totalConnections PCs connected"
        val endpoint = NetworkAddressDetector.detectEndpoints(preferHotspot = settings.preferHotspotIp).firstOrNull()

        val title: String
        val body: String
        if (activeSlots.size == 1) {
            val (slotNum, telemetry) = activeSlots.first()
            title = "FTP PC (FTP $slotNum) • $clientSummary"
            body = when (telemetry.runState) {
                ServerRunState.STARTING -> "Starting FTP $slotNum on port ${telemetry.boundPort}..."
                ServerRunState.RUNNING -> endpoint?.toFtpUrl(
                    telemetry.boundPort,
                    settings.username,
                    settings.allowAnonymous
                ) ?: "Listening on port ${telemetry.boundPort}"
                else -> "Port ${telemetry.boundPort}"
            }
        } else {
            val slotLabels = activeSlots.joinToString(", ") { (slotNum, t) ->
                "$slotNum:${t.boundPort}"
            }
            val hostPart = endpoint?.ipAddress ?: "0.0.0.0"
            title = "FTP PC (${runningSlots.size.coerceAtLeast(1)} active) • $clientSummary"
            body = "FTP $slotLabels • $hostPart"
        }

        val notif = buildNotification(
            title = title,
            urlText = body,
            readOnly = settings.readOnly
        )
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notif)
    }

    private fun promoteToForeground(title: String, urlText: String, readOnly: Boolean) {
        val notification = buildNotification(title, urlText, readOnly)
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
                ftpEngine.stopAll()
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
        title: String,
        urlText: String,
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

        val modeTag = if (readOnly) "Read-Only" else "Read/Write"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$title • $modeTag")
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
        if (::ftpEngine.isInitialized && ftpEngine.hasAnyActiveSlot()) {
            ftpEngine.stopAll()
        }
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }
}
