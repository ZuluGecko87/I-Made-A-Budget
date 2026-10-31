package com.z.imab

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.data.BudgetStore
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import com.z.imab.ui.EncryptionBanner
import com.z.imab.ui.EncryptionCard
import com.z.imab.ui.LightColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EncryptionReminderTest {
    private class Disk : Storage {
        override val defaultDeviceName = "test"
        val local = HashMap<String, String>()
        override suspend fun readLocal(name: String) = local[name]
        override suspend fun writeLocal(name: String, text: String) { local[name] = text }
        override suspend fun readSync(location: SyncLocation) = listOf("")
        override suspend fun writeSync(location: SyncLocation, text: String) {}
    }

    private val reminder = "Your budget file isn't encrypted"

    @Test
    fun dismissSilencesTheReminderAndSettingsBringsItBack() = runComposeUiTest {
        val disk = Disk()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = BudgetStore(disk, scope).also { runBlocking { it.start() } }
        setContent { MaterialTheme(colorScheme = LightColors) { Column { EncryptionBanner(store) {}; EncryptionCard(store) } } }

        onNodeWithText(reminder, substring = true).assertExists()
        onNodeWithText("Dismiss").performClick()
        waitForIdle()
        onNodeWithText(reminder, substring = true).assertDoesNotExist()
        assertFalse(store.settings.value.encryptionReminder)

        // It stays off after restarting the app.
        val reopened = BudgetStore(disk, scope).also { runBlocking { it.start() } }
        assertFalse(reopened.settings.value.encryptionReminder)

        // Settings → Encryption turns it back on.
        onNode(isToggleable()).performClick()
        waitForIdle()
        assertTrue(store.settings.value.encryptionReminder)
        onNodeWithText(reminder, substring = true).assertExists()
    }
}
