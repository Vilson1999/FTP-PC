package com.example.ftp

import com.example.data.AppSettings
import com.example.network.NetworkAddressDetector
import com.example.storage.VirtualStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.Ftplet
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.listener.ListenerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class ServerRunState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class FtpEngineTelemetry(
    val runState: ServerRunState = ServerRunState.STOPPED,
    val boundPort: Int = 2121,
    val activeConnections: Int = 0,
    val errorMessage: String? = null
)

class FtpServerEngine(
    private val storage: VirtualStorageManager
) {
    private companion object {
        const val LEGACY_SLOT_KEY = 0
    }

    private class SlotRuntime {
        @Volatile
        var ftpServer: FtpServer? = null
        var startJob: Job? = null
        val activeClientCount = AtomicInteger(0)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimes = ConcurrentHashMap<Int, SlotRuntime>()

    private val _slotTelemetries = MutableStateFlow(
        (1..4).associateWith { slot -> FtpEngineTelemetry(boundPort = 2120 + slot) }
    )
    val slotTelemetries: StateFlow<Map<Int, FtpEngineTelemetry>> = _slotTelemetries.asStateFlow()

    private val _telemetry = MutableStateFlow(FtpEngineTelemetry())
    val telemetry: StateFlow<FtpEngineTelemetry> = _telemetry.asStateFlow()

    @Volatile
    private var currentSettings = AppSettings()

    fun updateSettings(settings: AppSettings) {
        currentSettings = settings
        storage.showHiddenFiles = settings.showHiddenFiles
        storage.allowRecursiveDelete = settings.allowRecursiveDelete
        storage.overwriteExisting = settings.overwriteExisting
        storage.updateSlotStorageConfig(settings.selectedFtpSlot, settings.slotStorages)
    }

    fun hasAnyActiveSlot(): Boolean {
        val slotsActive = _slotTelemetries.value.values.any {
            it.runState == ServerRunState.RUNNING || it.runState == ServerRunState.STARTING
        }
        val legacyActive = _telemetry.value.runState == ServerRunState.RUNNING ||
            _telemetry.value.runState == ServerRunState.STARTING
        return slotsActive || legacyActive
    }

    @Synchronized
    fun startAll(settings: AppSettings, customPorts: Map<Int, Int>? = null) {
        updateSettings(settings)
        for (slot in 1..4) {
            startSlot(slot, settings, customPort = customPorts?.get(slot))
        }
    }

    @Synchronized
    fun startSlot(slot: Int, settings: AppSettings, customPort: Int? = null) {
        val clampedSlot = slot.coerceIn(1, 4)
        val targetPort = customPort ?: (2120 + clampedSlot)
        startInternal(
            runtimeKey = clampedSlot,
            boundSlot = clampedSlot,
            port = targetPort,
            settings = settings
        )
    }

    @Synchronized
    fun start(settings: AppSettings) {
        startInternal(
            runtimeKey = LEGACY_SLOT_KEY,
            boundSlot = null,
            port = settings.port,
            settings = settings
        )
    }

    private fun startInternal(
        runtimeKey: Int,
        boundSlot: Int?,
        port: Int,
        settings: AppSettings
    ) {
        updateSettings(settings)
        val existingState = getTelemetryForKey(runtimeKey)
        if (existingState.runState == ServerRunState.RUNNING ||
            existingState.runState == ServerRunState.STARTING
        ) {
            return
        }

        val runtime = runtimes.getOrPut(runtimeKey) { SlotRuntime() }
        updateTelemetryForKey(runtimeKey) {
            it.copy(
                runState = ServerRunState.STARTING,
                boundPort = port,
                errorMessage = null
            )
        }

        runtime.startJob = scope.launch {
            try {
                val serverFactory = FtpServerFactory()

                val dataConfigFactory = DataConnectionConfigurationFactory().apply {
                    isActiveEnabled = true
                    isActiveIpCheck = false
                    isPassiveIpCheck = false
                    idleTime = 300
                }

                val listenerFactory = ListenerFactory().apply {
                    this.port = port
                    serverAddress = "0.0.0.0"
                    dataConnectionConfiguration = dataConfigFactory.createDataConnectionConfiguration()
                }
                serverFactory.addListener("default", listenerFactory.createListener())

                val connectionConfigFactory = ConnectionConfigFactory().apply {
                    isAnonymousLoginEnabled = true
                    maxLogins = 20
                    maxAnonymousLogins = 20
                    maxThreads = 16
                }
                serverFactory.connectionConfig = connectionConfigFactory.createConnectionConfig()
                serverFactory.userManager = BeamUserManager { currentSettings }
                serverFactory.fileSystem = VirtualFileSystemFactory(
                    storage = storage,
                    isReadOnlyProvider = { currentSettings.readOnly },
                    bufferSizeKbProvider = { currentSettings.bufferSizeKb },
                    slotProvider = { boundSlot }
                )

                val ftplets = LinkedHashMap<String, Ftplet>()
                ftplets["beamTelemetry"] = BeamFtplet(runtimeKey, runtime)
                serverFactory.ftplets = ftplets

                val server = serverFactory.createServer()
                server.start()
                runtime.ftpServer = server

                val actualPort = runCatching {
                    serverFactory.listeners["default"]?.port ?: port
                }.getOrDefault(port)

                updateTelemetryForKey(runtimeKey) {
                    it.copy(
                        runState = ServerRunState.RUNNING,
                        boundPort = actualPort,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                runCatching { runtime.ftpServer?.stop() }
                runtime.ftpServer = null
                updateTelemetryForKey(runtimeKey) {
                    it.copy(
                        runState = ServerRunState.ERROR,
                        errorMessage = e.message ?: "Failed to start FTP server on port $port"
                    )
                }
            }
        }
    }

    @Synchronized
    fun stopSlot(slot: Int) {
        stopInternal(slot.coerceIn(1, 4))
    }

    @Synchronized
    fun stopAll() {
        val keys = (runtimes.keys + LEGACY_SLOT_KEY + (1..4)).distinct()
        for (key in keys) {
            stopInternal(key)
        }
    }

    @Synchronized
    fun stop() {
        stopAll()
    }

    private fun stopInternal(runtimeKey: Int) {
        val runtime = runtimes[runtimeKey]
        runtime?.startJob?.cancel()
        runtime?.startJob = null

        val runningServer = runtime?.ftpServer
        runtime?.ftpServer = null
        if (runningServer != null) {
            runCatching { runningServer.stop() }
        }

        runtime?.activeClientCount?.set(0)

        updateTelemetryForKey(runtimeKey) {
            it.copy(
                runState = ServerRunState.STOPPED,
                activeConnections = 0,
                errorMessage = null
            )
        }
    }

    private fun getTelemetryForKey(runtimeKey: Int): FtpEngineTelemetry {
        return if (runtimeKey == LEGACY_SLOT_KEY) {
            _telemetry.value
        } else {
            _slotTelemetries.value[runtimeKey] ?: FtpEngineTelemetry(boundPort = 2120 + runtimeKey)
        }
    }

    private fun updateTelemetryForKey(
        runtimeKey: Int,
        transform: (FtpEngineTelemetry) -> FtpEngineTelemetry
    ) {
        if (runtimeKey == LEGACY_SLOT_KEY) {
            _telemetry.update(transform)
        } else {
            _slotTelemetries.update { current ->
                val updated = current.toMutableMap()
                val base = updated[runtimeKey] ?: FtpEngineTelemetry(boundPort = 2120 + runtimeKey)
                updated[runtimeKey] = transform(base)
                updated
            }
            if (runtimeKey == currentSettings.selectedFtpSlot) {
                _telemetry.value = _slotTelemetries.value[runtimeKey] ?: FtpEngineTelemetry()
            }
        }
    }

    private inner class BeamFtplet(
        private val runtimeKey: Int,
        private val runtime: SlotRuntime
    ) : DefaultFtplet() {

        private fun FtpSession.clientIp(): String {
            return clientAddress?.address?.hostAddress ?: ""
        }

        override fun onConnect(session: FtpSession): FtpletResult {
            val ip = session.clientIp()
            if (ip.isNotEmpty() && !NetworkAddressDetector.isLocalPrivateIp(ip)) {
                return FtpletResult.DISCONNECT
            }
            val count = runtime.activeClientCount.incrementAndGet()
            updateTelemetryForKey(runtimeKey) { it.copy(activeConnections = count) }
            return FtpletResult.DEFAULT
        }

        override fun onDisconnect(session: FtpSession): FtpletResult {
            val remaining = runtime.activeClientCount.decrementAndGet().coerceAtLeast(0)
            updateTelemetryForKey(runtimeKey) { it.copy(activeConnections = remaining) }
            return FtpletResult.DEFAULT
        }
    }
}
