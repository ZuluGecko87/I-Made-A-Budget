package com.z.imab

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.data.BudgetStore
import com.z.imab.model.Account
import com.z.imab.platform.PickedFile
import com.z.imab.platform.Pickers
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import com.z.imab.ui.App
import com.z.imab.ui.NavState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Clicks through the Import screen the way a person would after dropping a bank file. */
@OptIn(ExperimentalTestApi::class)
class ImportAddNowTest {
    private class MemStorage : Storage {
        val files = HashMap<String, String>()
        override val defaultDeviceName = "Test"
        override suspend fun readLocal(name: String) = files[name]
        override suspend fun writeLocal(name: String, text: String) { files[name] = text }
        override suspend fun readSync(location: SyncLocation) = listOf("")
        override suspend fun writeSync(location: SyncLocation, text: String) {}
    }

    private object NoPickers : Pickers {
        override suspend fun pickImportFile(): PickedFile? = null
        override suspend fun createSyncFile(): SyncLocation? = null
        override suspend fun openSyncFile(): SyncLocation? = null
        override val syncHint = ""
        override suspend fun saveFile(suggestedName: String, text: String): String? = null
        override suspend fun pickArchive(): PickedFile? = null
    }

    @Test
    fun addTransactionAddsItNowAndTakesItOffTheList() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        store.budget.saveAccount(Account("chk", "Everyday Checking"), 0, LocalDate(2026, 9, 1))
        val nav = NavState().apply { openAccount("chk") }
        setContent { App(store, NoPickers, nav, showSplash = false) }
        nav.importFile(PickedFile("Checking_Oct.qbo", Samples.QBO_CHECKING))
        waitForIdle()

        val rows = onAllNodesWithText("Add transaction").fetchSemanticsNodes().size
        val before = store.budget.data.transactionsByAccount["chk"].orEmpty().size
        onAllNodesWithText("Add transaction")[0].performClick()
        waitForIdle()

        assertEquals(before + 1, store.budget.data.transactionsByAccount["chk"].orEmpty().size)
        assertEquals(rows - 1, onAllNodesWithText("Add transaction").fetchSemanticsNodes().size)
        onNodeWithText("1 transaction(s) already added.").assertExists()
    }
}
