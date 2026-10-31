package com.z.imab

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.data.BudgetStore
import com.z.imab.data.Vault
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import com.z.imab.ui.EncryptionCard
import com.z.imab.ui.LightColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EncryptionUiTest {
    private class Disk : Storage {
        override val defaultDeviceName = "test"
        val local = HashMap<String, String>()
        override suspend fun readLocal(name: String) = local[name]
        override suspend fun writeLocal(name: String, text: String) { local[name] = text }
        override suspend fun readSync(location: SyncLocation) = listOf("")
        override suspend fun writeSync(location: SyncLocation, text: String) {}
        override suspend fun saveKey(value: String) = true
    }

    @Test
    fun settingAPassphraseFromSettings() = runComposeUiTest {
        val disk = Disk()
        val store = BudgetStore(disk, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        setContent { MaterialTheme(colorScheme = LightColors) { EncryptionCard(store) } }

        onNodeWithText("Set a passphrase…").performClick()
        val fields = onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("short")
        onNodeWithText("Use at least ${Vault.MIN_PASSPHRASE} characters.").assertExists()
        fields[0].performTextReplacement("my long passphrase")
        fields[1].performTextReplacement("my long passphrasf")
        onNodeWithText("The two don't match.").assertExists()
        onNodeWithText("Encrypt").assertIsNotEnabled()
        fields[1].performTextReplacement("my long passphrase")
        onNodeWithText("Encrypt").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 20_000) { store.encrypted.value && runCatching { onNodeWithText("Budget encrypted.").assertExists() }.isSuccess }

        assertTrue(Vault.isEncrypted(disk.local.getValue(BudgetStore.LEDGER_FILE)))
        onNodeWithText("Change passphrase…").assertExists()
    }
}
