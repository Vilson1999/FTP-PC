package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ftp.ServerRunState
import com.example.storage.StoragePermissionHelper
import com.example.ui.screens.ServerDashboardScreen
import com.example.ui.theme.BeamFtpTheme
import kotlinx.coroutines.launch

@Composable
fun BeamFtpApp(
    viewModel: ServerViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val slotTelemetries by viewModel.slotTelemetries.collectAsStateWithLifecycle()
    val endpoints by viewModel.endpoints.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingPickerSlot by rememberSaveable { mutableIntStateOf(1) }
    var showStoragePermissionDialog by remember { mutableStateOf(false) }
    var hasPromptedStorageOnStart by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setUiForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setUiForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val allFilesAccessLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val requestStoragePermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesAccessLauncher.launch(StoragePermissionHelper.createAllFilesAccessIntent(context))
        } else {
            legacyStoragePermissionLauncher.launch(StoragePermissionHelper.legacyPermissions())
        }
    }

    LaunchedEffect(Unit) {
        if (!StoragePermissionHelper.hasFullStoragePermission(context) && !hasPromptedStorageOnStart) {
            hasPromptedStorageOnStart = true
            showStoragePermissionDialog = true
        }
    }

    val storagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pickedUri = result.data?.data
            if (pickedUri != null) {
                val targetSlot = pendingPickerSlot
                viewModel.onSlotStorageUriPicked(targetSlot, pickedUri) { label ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.storage_location_updated, targetSlot, label)
                        )
                    }
                }
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.toggleSlotServer(settings.selectedFtpSlot)
    }

    val startServerWithNotificationCheck: () -> Unit = {
        val activeSlot = settings.selectedFtpSlot.coerceIn(1, 4)
        val slotState = slotTelemetries[activeSlot]?.runState ?: ServerRunState.STOPPED
        val isStarting = slotState == ServerRunState.STOPPED || slotState == ServerRunState.ERROR
        if (isStarting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.toggleSlotServer(activeSlot)
            }
        } else {
            viewModel.toggleSlotServer(activeSlot)
        }
    }

    val onCopyUrl: (String) -> Unit = { url ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FTP PC URL", url))
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.url_copied))
        }
    }

    BeamFtpTheme {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            ServerDashboardScreen(
                settings = settings,
                slotTelemetries = slotTelemetries,
                endpoints = endpoints,
                onSelectFtpSlot = viewModel::selectFtpSlot,
                onToggleServer = startServerWithNotificationCheck,
                onCopyUrl = onCopyUrl,
                onToggleRunInBackground = viewModel::setRunInBackground,
                onChooseStorage = { slot ->
                    pendingPickerSlot = slot
                    storagePickerLauncher.launch(viewModel.createSlotStoragePickerIntent(slot))
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }

        if (showStoragePermissionDialog) {
            AlertDialog(
                onDismissRequest = {
                    showStoragePermissionDialog = false
                },
                title = {
                    Text(text = stringResource(R.string.storage_permission_dialog_title))
                },
                text = {
                    Text(text = stringResource(R.string.storage_permission_dialog_body))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showStoragePermissionDialog = false
                            requestStoragePermission()
                        },
                        modifier = Modifier.testTag("dialog_allow_storage_button")
                    ) {
                        Text(text = stringResource(R.string.allow))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showStoragePermissionDialog = false
                        },
                        modifier = Modifier.testTag("dialog_skip_storage_button")
                    ) {
                        Text(text = stringResource(R.string.not_now))
                    }
                }
            )
        }
    }
}
