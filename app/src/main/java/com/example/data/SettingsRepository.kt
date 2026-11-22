package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "beamftp_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SELECTED_FTP_SLOT = intPreferencesKey("selected_ftp_slot")
        val RUN_IN_BACKGROUND = booleanPreferencesKey("run_in_background")
        val INTERNAL_URI = stringPreferencesKey("internal_storage_uri")
        val SD_CARD_URI = stringPreferencesKey("external_sd_card_uri")

        fun slotStorageUriKey(slot: Int) = stringPreferencesKey("slot_storage_uri_${slot.coerceIn(1, 4)}")
        fun slotStorageNameKey(slot: Int) = stringPreferencesKey("slot_storage_name_${slot.coerceIn(1, 4)}")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val slot = (prefs[Keys.SELECTED_FTP_SLOT] ?: 1).coerceIn(1, 4)
        val slotPort = 2120 + slot
        val legacyInternalUri = prefs[Keys.INTERNAL_URI]?.takeIf { it.isNotBlank() }
        val legacySdUri = prefs[Keys.SD_CARD_URI]?.takeIf { it.isNotBlank() }

        val slotStorages = (1..4).associateWith { index ->
            val savedUri = prefs[Keys.slotStorageUriKey(index)]?.takeIf { it.isNotBlank() }
                ?: when (index) {
                    1 -> legacyInternalUri
                    2 -> legacySdUri
                    else -> null
                }
            val savedName = prefs[Keys.slotStorageNameKey(index)]?.takeIf { it.isNotBlank() }
                ?: savedUri?.let { runCatching { extractFriendlyFolderName(Uri.parse(it)) }.getOrNull() }
            FtpSlotStorage(uriString = savedUri, displayName = savedName)
        }

        AppSettings(
            selectedFtpSlot = slot,
            slotStorages = slotStorages,
            runInBackground = prefs[Keys.RUN_IN_BACKGROUND] ?: true,
            port = slotPort
        )
    }

    suspend fun setSelectedFtpSlot(slot: Int) {
        val clamped = slot.coerceIn(1, 4)
        context.dataStore.edit { prefs ->
            prefs[Keys.SELECTED_FTP_SLOT] = clamped
        }
    }

    suspend fun setRunInBackground(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RUN_IN_BACKGROUND] = value
        }
    }

    suspend fun setSlotStorageUri(slot: Int, uri: Uri): String {
        val clamped = slot.coerceIn(1, 4)
        val label = extractFriendlyFolderName(uri)
        context.dataStore.edit { prefs ->
            prefs[Keys.slotStorageUriKey(clamped)] = uri.toString()
            prefs[Keys.slotStorageNameKey(clamped)] = label
        }
        return label
    }

    private fun extractFriendlyFolderName(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        val rawName = when {
            docId.equals("primary:", ignoreCase = true) -> "Internal Storage"
            docId.endsWith(":") && docId.isNotBlank() -> "SD Card"
            docId.contains(":") -> docId.substringAfterLast(":").substringAfterLast("/")
            else -> treeUri.lastPathSegment?.substringAfterLast("/") ?: "Selected Folder"
        }
        return rawName.replace("/", "_").replace("|", "_").ifBlank { "Selected Folder" }
    }
}
