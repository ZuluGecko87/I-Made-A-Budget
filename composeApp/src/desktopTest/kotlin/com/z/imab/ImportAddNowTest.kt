package com.z.imab

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.data.BudgetStore
import com.z.imab.model.Account
import com.z.imab.model.SplitLine
import com.z.imab.model.Transaction
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
import kotlin.test.assertTrue

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
        store.setEncryptionReminder(false) // the banner isn't what this tests, and it takes room on screen
        store.budget.saveAccount(Account("chk", "Everyday Checking"), 0, LocalDate(2026, 9, 1))
        val nav = NavState().apply { openAccount("chk") }
        setContent { App(store, NoPickers, nav, showSplash = false) }
        nav.importFile(PickedFile("Checking_Oct.qbo", Samples.QBO_CHECKING))
        waitForIdle()

        // The first row is the Kroger charge.
        onNodeWithText("KROGER #123").assertExists()
        val before = store.budget.data.transactionsByAccount["chk"].orEmpty().size
        onAllNodesWithText("Add transaction")[0].performClick()
        waitForIdle()

        assertEquals(before + 1, store.budget.data.transactionsByAccount["chk"].orEmpty().size)
        onNodeWithText("KROGER #123").assertDoesNotExist()
        onNodeWithText("1 added already.").assertExists()
    }

    @Test
    fun mergeKeepsOneClearedTransactionLabelledMerged() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        store.setEncryptionReminder(false) // the banner isn't what this tests, and it takes room on screen
        val b = store.budget
        b.saveAccount(Account("chk", "Everyday Checking"), 0, LocalDate(2026, 9, 1))
        // Entered by hand a day after the bank's Kroger charge on 10/01.
        b.saveTransaction(Transaction("hand", "chk", LocalDate(2026, 10, 2), -4512, "Kroger", categoryId = "seed-groceries"))
        val nav = NavState().apply { openAccount("chk") }
        setContent { App(store, NoPickers, nav, showSplash = false) }
        nav.importFile(PickedFile("Checking_Oct.qbo", Samples.QBO_CHECKING))
        waitForIdle()

        // Kroger matches what was entered; only the two new rows ask for a category.
        assertEquals(2, onAllNodesWithText("Category").fetchSemanticsNodes().size)
        onNodeWithText("Merge with what you entered").performClick()
        waitForIdle()

        val kroger = b.data.transactionsByAccount["chk"].orEmpty().filter { it.amount == -4512L }
        assertEquals(1, kroger.size)
        val t = kroger.single()
        assertEquals("hand", t.id)
        assertTrue(t.merged && t.cleared)
        assertEquals("seed-groceries", t.categoryId)
        assertEquals(LocalDate(2026, 10, 1), t.date)
        onNodeWithText("Merge with what you entered").assertDoesNotExist()
        onNodeWithText("1 merged or matched already.").assertExists()
    }

    @Test
    fun splitARowBeforeAddingIt() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        store.setEncryptionReminder(false)
        store.budget.saveAccount(Account("chk", "Everyday Checking"), 0, LocalDate(2026, 9, 1))
        val nav = NavState().apply { openAccount("chk") }
        setContent { App(store, NoPickers, nav, showSplash = false) }
        nav.importFile(PickedFile("Checking_Oct.qbo", Samples.QBO_CHECKING))
        waitForIdle()

        fun pick(field: String, option: String) {
            onNodeWithText(field).performClick()
            waitForIdle()
            onAllNodes(hasSetTextAction()).onLast().performTextInput(option)
            waitForIdle()
            onAllNodesWithText(option).onLast().performClick()
            waitForIdle()
        }

        // The Kroger charge is $45.12.
        onAllNodesWithText("Split")[0].performClick()
        waitForIdle()
        onNodeWithText("Split $45.12").assertExists()
        pick("Category 1", "Groceries")
        onNode(hasSetTextAction() and hasText("Amount 1")).performTextReplacement("40")
        pick("Category 2", "Household")
        onNodeWithText("Fill in the rest").performClick()
        waitForIdle()
        onNodeWithText("Done").performClick()
        waitForIdle()
        onNodeWithText("Split: Groceries, Household").assertExists()

        onAllNodesWithText("Add transaction")[0].performClick()
        waitForIdle()
        val t = store.budget.data.transactionsByAccount["chk"].orEmpty().single { it.amount == -4512L }
        assertEquals(listOf(SplitLine("seed-groceries", -4000), SplitLine("seed-household", -512)), t.splits)
    }
}
