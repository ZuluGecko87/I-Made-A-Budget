package com.z.imab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.z.imab.data.BudgetStore
import com.z.imab.data.LockState
import com.z.imab.data.Vault
import kotlinx.coroutines.launch

private const val NO_RECOVERY =
    "There's no way to recover a forgotten passphrase: keep it in a password manager. Devices that " +
        "already know it keep working."

@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit, label: String, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Asks for the passphrase. [onSubmit] returns an error to show, or null when it worked. With
 * [confirm] it's typed twice (setting a new passphrase).
 */
@Composable
fun PassphraseDialog(
    title: String,
    explanation: String,
    confirm: Boolean,
    action: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (String) -> String?,
) {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val problem = when {
        !confirm -> null
        pass.isNotEmpty() && pass.length < Vault.MIN_PASSPHRASE -> "Use at least ${Vault.MIN_PASSPHRASE} characters."
        again.isNotEmpty() && again != pass -> "The two don't match."
        else -> null
    }
    val canSubmit = !busy && pass.isNotEmpty() && (!confirm || (problem == null && again == pass))
    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        scope.launch {
            error = onSubmit(pass)
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(explanation, style = MaterialTheme.typography.bodySmall)
                PassphraseField(pass, { pass = it }, "Passphrase", onDone = if (confirm) null else ::submit)
                if (confirm) PassphraseField(again, { again = it }, "Type it again", onDone = ::submit)
                (error ?: problem)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text("Working…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = canSubmit) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** Shown instead of the app when this device's own copy is encrypted and the key isn't on the device. */
@Composable
fun UnlockScreen(store: BudgetStore) {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun submit() {
        if (busy || pass.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            if (!store.unlock(pass)) error = "That passphrase doesn't open this budget."
            busy = false
        }
    }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Text("Your budget is encrypted", style = MaterialTheme.typography.headlineSmall)
            Text("Enter the passphrase to open it. This device will remember it.", style = MaterialTheme.typography.bodyMedium)
            PassphraseField(pass, { pass = it }, "Passphrase", onDone = ::submit)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::submit, enabled = !busy && pass.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Unlocking…" else "Unlock")
            }
        }
    }
}

/**
 * Over the app: asks for the passphrase when another device encrypted the sync file (or changed the
 * passphrase), and suggests setting one while the budget files aren't encrypted.
 */
@Composable
fun EncryptionBanner(store: BudgetStore, onSetUp: () -> Unit) {
    val lock by store.lock.collectAsState()
    val encrypted by store.encrypted.collectAsState()
    val settings by store.settings.collectAsState()
    var asking by remember { mutableStateOf(false) }
    val syncLocked = (lock as? LockState.NeedsPassphrase)?.local == false
    when {
        syncLocked -> BannerCard(
            "Your synced budget is encrypted with a passphrase this device doesn't have yet. Enter it to keep syncing.",
            "Enter passphrase",
        ) { asking = true }
        !encrypted && settings.encryptionReminder -> BannerCard(
            "Your budget file isn't encrypted. Set a passphrase so it can't be read by anyone with access to your " +
                "cloud storage. Dismiss to stop this reminder (Settings → Encryption brings it back).",
            "Set passphrase",
            onDismiss = { store.setEncryptionReminder(false) },
        ) { onSetUp() }
    }
    if (asking) {
        PassphraseDialog(
            title = "Enter passphrase",
            explanation = "Another device encrypted the budget. Enter the passphrase you set there.",
            confirm = false,
            action = "Unlock",
            onDismiss = { asking = false },
        ) { p -> if (store.unlock(p)) { asking = false; null } else "That passphrase doesn't open the synced budget." }
    }
}

@Composable
private fun BannerCard(text: String, action: String, onDismiss: (() -> Unit)? = null, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                onDismiss?.let { TextButton(onClick = it) { Text("Dismiss") } }
                Button(onClick = onAction) { Text(action) }
            }
        }
    }
}

/** Settings card: set or change the passphrase that encrypts the budget files. */
@Composable
fun EncryptionCard(store: BudgetStore, openSetUp: Boolean = false, onSetUpShown: () -> Unit = {}) {
    val encrypted by store.encrypted.collectAsState()
    val remembered by store.keyRemembered.collectAsState()
    var setting by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    if (openSetUp && !setting) {
        setting = true
        onSetUpShown()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, null, tint = if (encrypted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                Text("Encryption", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (encrypted) {
                Text(
                    "Your budget files (the sync file, this device's copy and archives) are encrypted with your passphrase " +
                        "(AES-256). " + if (remembered) "This device keeps the key in its secure storage." else
                        "This device can't store the key securely, so the passphrase is asked each time IMAB opens.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { setting = true }) { Text("Change passphrase…") }
            } else {
                Text(
                    "Your budget files aren't encrypted: anyone who can open your OneDrive, Google Drive or Dropbox can read them. " +
                        "Set a passphrase to encrypt them (AES-256). Each of your devices asks for it once.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { setting = true }) { Text("Set a passphrase…") }
                val settings by store.settings.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(settings.encryptionReminder, { store.setEncryptionReminder(it) })
                    Text("Remind me on every screen until it's encrypted", style = MaterialTheme.typography.bodyMedium)
                }
            }
            done?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (setting) {
        PassphraseDialog(
            title = if (encrypted) "Change passphrase" else "Encrypt your budget",
            explanation = (if (encrypted) "Your other devices will ask for the new passphrase the next time they sync. " else
                "Your other devices will ask for this passphrase the next time they sync. ") + NO_RECOVERY,
            confirm = true,
            action = if (encrypted) "Change" else "Encrypt",
            onDismiss = { setting = false },
        ) { p ->
            runCatching { store.setPassphrase(p) }.fold(
                onSuccess = { setting = false; done = "Budget encrypted."; null },
                onFailure = { it.message ?: "Couldn't encrypt the budget." },
            )
        }
    }
}
