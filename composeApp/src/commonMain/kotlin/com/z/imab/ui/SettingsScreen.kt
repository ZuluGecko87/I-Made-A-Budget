package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.z.imab.data.BudgetStore
import com.z.imab.data.SyncStatus
import com.z.imab.platform.Pickers
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun SettingsScreen(store: BudgetStore, pickers: Pickers) {
    val settings by store.settings.collectAsState()
    val sync by store.syncStatus.collectAsState()
    val data by store.data.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var deviceName by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }

    fun pick(block: suspend () -> com.z.imab.platform.SyncLocation?) {
        scope.launch {
            error = null
            try {
                block()?.let(store::connectSync)
            } catch (e: Exception) {
                error = e.message ?: "Couldn't use that location."
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "IMAB keeps your budget in a single file that you store in OneDrive, Google Drive or Dropbox. " +
                        "Every device that opens the same file stays in sync. On the first device choose \"Create sync file\"; " +
                        "on the others choose \"Use existing sync file\" and pick that same file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(pickers.syncHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Location: " + (settings.syncName ?: "not set — data is only on this device"),
                    fontWeight = FontWeight.Medium,
                )
                Text(syncText(sync), color = if (sync is SyncStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (settings.syncId != null) {
                    Picker(
                        "Auto-sync",
                        listOf(1, 5, 15, 30).map { PickerOption(it, if (it == 1) "Every minute" else "Every $it minutes") },
                        settings.syncIntervalMinutes,
                        store::setSyncInterval,
                        Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                    )
                    Text(
                        "Also syncs when the app opens, about a second after each change, when you come back to the app, " +
                            "and as soon as a newer version of the file arrives from another device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val location = settings.syncId?.let { com.z.imab.platform.SyncLocation(it, settings.syncName ?: it) }
                val cloudApp = location?.let(pickers::syncAppName)
                if (location != null && cloudApp != null) {
                    Text(
                        "On this phone the $cloudApp app uploads the budget file, and Android may hold that back while " +
                            "$cloudApp is in the background. If another device doesn't see a change, open $cloudApp " +
                            "to upload it now (setting $cloudApp's battery use to Unrestricted helps too).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { pickers.openSyncApp(location) }) { Text("Open $cloudApp to upload now") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pick { pickers.createSyncFile() } }) { Text("Create sync file…") }
                    OutlinedButton(onClick = { pick { pickers.openSyncFile() } }) { Text("Use existing sync file…") }
                    if (settings.syncId != null) {
                        OutlinedButton(onClick = store::requestSync, enabled = sync !is SyncStatus.Syncing) { Text("Sync now") }
                        TextButton(onClick = store::disconnectSync) { Text("Stop syncing") }
                    }
                }
            }
        }

        RulesCard(data, store.budget)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Picker(
                    "Theme",
                    listOf(
                        PickerOption("system", "Match system"),
                        PickerOption("light", "Light"),
                        PickerOption("dark", "Dark"),
                    ),
                    settings.theme,
                    store::setTheme,
                    Modifier.widthIn(max = 320.dp).fillMaxWidth(),
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    deviceName, { deviceName = it },
                    label = { Text("Device name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (deviceName != settings.deviceName && deviceName.isNotBlank()) {
                    TextButton(onClick = { store.setDeviceName(deviceName.trim()) }) { Text("Save name") }
                }
                Text(
                    "${data.accounts.size} accounts · ${data.categories.size} categories · ${data.transactions.size} transactions · ${data.scheduled.size} scheduled",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(
            "IMAB — I Made A Budget · v1.0.0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun syncText(s: SyncStatus): String = when (s) {
    SyncStatus.NotConfigured -> "Sync is off."
    SyncStatus.Idle -> "Waiting to sync…"
    SyncStatus.Syncing -> "Syncing…"
    is SyncStatus.Synced -> "Last synced ${time(s.atMillis)}" +
        (if (s.pulled > 0) " · received ${s.pulled} change(s)" else "") +
        (if (s.pushed) " · uploaded changes" else " · up to date")
    is SyncStatus.Failed -> "Sync failed at ${time(s.atMillis)}: ${s.message}"
}

private fun time(ms: Long): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.date} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}
