package com.example.data

data class FtpSlotStorage(
    val uriString: String? = null,
    val displayName: String? = null
)

data class AppSettings(
    // Active FTP Profile Slot (1..4 -> ports 2121..2124)
    val selectedFtpSlot: Int = 1,
    val slotStorages: Map<Int, FtpSlotStorage> = emptyMap(),
    val runInBackground: Boolean = true,

    // FTP Server Core Config
    val allowAnonymous: Boolean = true,
    val username: String = "pc",
    val password: String = "beam2121",
    val readOnly: Boolean = false,
    val showHiddenFiles: Boolean = false,
    val port: Int = 2121,
    val preferHotspotIp: Boolean = true,
    val keepWakeLock: Boolean = true,
    val allowRecursiveDelete: Boolean = true,
    val overwriteExisting: Boolean = true,
    val bufferSizeKb: Int = 64
) {
    fun storageForSlot(slot: Int): FtpSlotStorage {
        val clamped = slot.coerceIn(1, 4)
        return slotStorages[clamped] ?: FtpSlotStorage()
    }

    fun storageLabelForSlot(slot: Int): String {
        val clamped = slot.coerceIn(1, 4)
        val customName = storageForSlot(clamped).displayName
        if (!customName.isNullOrBlank()) return customName
        return when (clamped) {
            1 -> "Internal Storage (Default)"
            2 -> "SD Card (Default)"
            3 -> "Download Folder (Default)"
            else -> "App Storage (Default)"
        }
    }
}
