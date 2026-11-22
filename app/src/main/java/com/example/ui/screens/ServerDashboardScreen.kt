package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AppSettings
import com.example.ftp.FtpEngineTelemetry
import com.example.ftp.ServerRunState
import com.example.network.NetworkEndpoint
import com.example.ui.theme.JetBrainsMonoFamily

private val StartGreen = Color(0xFF16A34A)
private val StopRed = Color(0xFFDC2626)

@Composable
fun ServerDashboardScreen(
    settings: AppSettings,
    telemetry: FtpEngineTelemetry,
    endpoints: List<NetworkEndpoint>,
    onSelectFtpSlot: (Int) -> Unit,
    onToggleServer: () -> Unit,
    onCopyUrl: (String) -> Unit,
    onToggleRunInBackground: (Boolean) -> Unit,
    onChooseStorage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = telemetry.runState == ServerRunState.RUNNING ||
        telemetry.runState == ServerRunState.STARTING
    val isOnline = telemetry.runState == ServerRunState.RUNNING
    val activeSlot = settings.selectedFtpSlot.coerceIn(1, 4)
    val slotPort = 2120 + activeSlot
    val activeEndpoint = endpoints.firstOrNull()
    val ftpUrl = activeEndpoint?.toFtpUrl(slotPort, settings.username, settings.allowAnonymous)
        ?: "ftp://0.0.0.0:$slotPort/"

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.home_app_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.testTag("app_title_text")
                )

                Text(
                    text = stringResource(if (isOnline) R.string.status_online else R.string.status_offline),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOnline) StartGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ftp_status_text")
                )
            }

            UnifiedFtpControlCard(
                selectedSlot = activeSlot,
                isActive = isActive,
                ftpUrl = ftpUrl,
                hasEndpoint = activeEndpoint != null,
                errorMessage = telemetry.errorMessage,
                onSelectSlot = onSelectFtpSlot,
                onCopyUrl = { onCopyUrl(ftpUrl) },
                onToggleServer = onToggleServer
            )

            RunBackgroundToggleCard(
                runInBackground = settings.runInBackground,
                onToggle = onToggleRunInBackground
            )

            SelectStorageCard(
                settings = settings,
                onChooseStorage = onChooseStorage
            )

            Text(
                text = stringResource(R.string.developer_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp, bottom = 8.dp)
                    .testTag("developer_attribution_text")
            )
        }
    }
}

@Composable
private fun FtpSlotPillsRow(
    selectedSlot: Int,
    testTagPrefix: String,
    onSelectSlot: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (slot in 1..4) {
            val isSelected = selectedSlot == slot
            val containerColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                label = "${testTagPrefix}_bg_$slot"
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "${testTagPrefix}_fg_$slot"
            )

            Surface(
                shape = RoundedCornerShape(50),
                color = containerColor,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(50))
                    .border(
                        width = if (isSelected) 0.dp else 1.dp,
                        color = if (isSelected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(50)
                    )
                    .clickable { onSelectSlot(slot) }
                    .testTag("${testTagPrefix}_$slot")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "FTP $slot",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = contentColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun UnifiedFtpControlCard(
    selectedSlot: Int,
    isActive: Boolean,
    ftpUrl: String,
    hasEndpoint: Boolean,
    errorMessage: String?,
    onSelectSlot: (Int) -> Unit,
    onCopyUrl: () -> Unit,
    onToggleServer: () -> Unit
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FtpSlotPillsRow(
                selectedSlot = selectedSlot,
                testTagPrefix = "ftp_pill",
                onSelectSlot = onSelectSlot
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_link),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "FTP URL",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable(enabled = hasEndpoint, onClick = onCopyUrl)
                ) {
                    Text(
                        text = ftpUrl,
                        fontFamily = JetBrainsMonoFamily,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 14.dp)
                            .testTag("ftp_url_text")
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            val buttonColor by animateColorAsState(
                targetValue = if (isActive) StopRed else StartGreen,
                label = "startStopPillColor"
            )

            Button(
                onClick = onToggleServer,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .testTag("toggle_server_button")
            ) {
                Icon(
                    painter = painterResource(if (isActive) R.drawable.ic_stop else R.drawable.ic_play_arrow),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isActive) "STOP" else "START",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun RunBackgroundToggleCard(
    runInBackground: Boolean,
    onToggle: (Boolean) -> Unit
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable { onToggle(!runInBackground) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (runInBackground) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_layers),
                    contentDescription = null,
                    tint = if (runInBackground) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Run App Background",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (runInBackground) {
                        "ON • Runs in background only while FTP is active"
                    } else {
                        "OFF • Stops when you leave the app"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = runInBackground,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("toggle_run_in_background")
            )
        }
    }
}

@Composable
private fun SelectStorageCard(
    settings: AppSettings,
    onChooseStorage: (Int) -> Unit
) {
    var selectedStorageSlot by rememberSaveable { mutableIntStateOf(1) }
    val clampedSlot = selectedStorageSlot.coerceIn(1, 4)
    val locationLabel = settings.storageLabelForSlot(clampedSlot)

    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("select_storage_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_sd_storage),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.select_storage_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("select_storage_header")
                )
            }

            FtpSlotPillsRow(
                selectedSlot = clampedSlot,
                testTagPrefix = "storage_ftp_pill",
                onSelectSlot = { selectedStorageSlot = it }
            )

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(12.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder_open),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "FTP $clampedSlot: $locationLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("storage_slot_location_text")
                    )
                }
            }

            FilledTonalButton(
                onClick = { onChooseStorage(clampedSlot) },
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("choose_storage_button")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_create_new_folder),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.choose_storage_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
