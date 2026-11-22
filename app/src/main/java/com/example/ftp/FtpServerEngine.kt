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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _telemetry = MutableStateFlow(FtpEngineTelemetry())
    val telemetry: StateFlow<FtpEngineTelemetry> = _telemetry.asStateFlow()

    @Volatile
    private var currentSettings = AppSettings()

    @Volatile
    private var ftpServer: FtpServer? = null

    private var startJob: Job? = null

    private val activeClientCount = AtomicInteger(0)

    fun updateSettings(settings: AppSettings) {
        currentSettings = settings
        storage.showHiddenFiles = settings.showHiddenFiles
        storage.allowRecursiveDelete = settings.allowRecursiveDelete
        storage.overwriteExisting = settings.overwriteExisting
        storage.updateSlotStorageConfig(settings.selectedFtpSlot, settings.slotStorages)
    }

    @Synchronized
    fun start(settings: AppSettings) {
        updateSettings(settings)
        if (_telemetry.value.runState == ServerRunState.RUNNING ||
            _telemetry.value.runState == ServerRunState.STARTING
        ) {
            return
        }

        _telemetry.update {
            it.copy(
                runState = ServerRunState.STARTING,
                boundPort = settings.port,
                errorMessage = null
            )
        }

        startJob = scope.launch {
            try {
                val serverFactory = FtpServerFactory()

                val dataConfigFactory = DataConnectionConfigurationFactory().apply {
                    isActiveEnabled = true
                    isActiveIpCheck = false
                    isPassiveIpCheck = false
                    idleTime = 300
                }

                val listenerFactory = ListenerFactory().apply {
                    port = settings.port
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
                    bufferSizeKbProvider = { currentSettings.bufferSizeKb }
                )

                val ftplets = LinkedHashMap<String, Ftplet>()
                ftplets["beamTelemetry"] = BeamFtplet()
                serverFactory.ftplets = ftplets

                val server = serverFactory.createServer()
                server.start()
                ftpServer = server

                val actualPort = runCatching {
                    serverFactory.listeners["default"]?.port ?: settings.port
                }.getOrDefault(settings.port)

                _telemetry.update {
                    it.copy(
                        runState = ServerRunState.RUNNING,
                        boundPort = actualPort,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                runCatching { ftpServer?.stop() }
                ftpServer = null
                _telemetry.update {
                    it.copy(
                        runState = ServerRunState.ERROR,
                        errorMessage = e.message ?: "Failed to start FTP server on port ${settings.port}"
                    )
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        startJob?.cancel()
        startJob = null

        val runningServer = ftpServer
        ftpServer = null
        if (runningServer != null) {
            runCatching { runningServer.stop() }
        }

        activeClientCount.set(0)

        _telemetry.update {
            it.copy(
                runState = ServerRunState.STOPPED,
                activeConnections = 0,
                errorMessage = null
            )
        }
    }

    private inner class BeamFtplet : DefaultFtplet() {

        private fun FtpSession.clientIp(): String {
            return clientAddress?.address?.hostAddress ?: ""
        }

        override fun onConnect(session: FtpSession): FtpletResult {
            val ip = session.clientIp()
            if (ip.isNotEmpty() && !NetworkAddressDetector.isLocalPrivateIp(ip)) {
                return FtpletResult.DISCONNECT
            }
            val count = activeClientCount.incrementAndGet()
            _telemetry.update { it.copy(activeConnections = count) }
            return FtpletResult.DEFAULT
        }

        override fun onDisconnect(session: FtpSession): FtpletResult {
            val remaining = activeClientCount.decrementAndGet().coerceAtLeast(0)
            _telemetry.update { it.copy(activeConnections = remaining) }
            return FtpletResult.DEFAULT
        }
    }
}
