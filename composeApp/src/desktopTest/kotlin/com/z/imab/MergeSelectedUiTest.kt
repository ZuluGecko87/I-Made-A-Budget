package com.z.imab

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.data.BudgetStore
import com.z.imab.model.Account
import com.z.imab.model.Transaction
import com.z.imab.platform.PickedFile
import com.z.imab.platform.Pickers
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import com.z.imab.ui.App
import com.z.imab.ui.NavState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/** Picking duplicates in an account and merging them. */
@OptIn(ExperimentalTestApi::class)
class MergeSelectedUiTest {
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
    fun selectTwoAndMergeThem() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        store.setEncryptionReminder(false)
        val b = store.budget
        b.saveAccount(Account("chk", "Everyday Checking"), 50000, LocalDate(2026, 9, 1))
        b.saveTransaction(Transaction("a", "chk", LocalDate(2026, 10, 3), -2500, "Corner Bakery", categoryId = "seed-dining-out"))
        b.saveTransaction(Transaction("b", "chk", LocalDate(2026, 10, 4), -2500, "CORNER BAKERY 0042"))
        val nav = NavState().apply { openAccount("chk") }
        setContent { App(store, NoPickers, nav, showSplash = false) }
        waitForIdle()

        onNodeWithText("Merge duplicates").performClick()
        waitForIdle()
        onNodeWithText("Tick the copies of the same transaction").assertExists()
        onNodeWithText("Merge…").assertIsNotEnabled()
        onNodeWithText("Corner Bakery").performClick()
        onNodeWithText("CORNER BAKERY 0042").performClick()
        waitForIdle()
        onNodeWithText("2 selected").assertExists()
        onNodeWithText("Merge…").assertIsEnabled().performClick()
        waitForIdle()

        // The one with a category is suggested; the balance goes back up by the duplicate.
        onNodeWithText("Merge 2 transactions into one").assertExists()
        onNodeWithText("The balance changes by $25.00.").assertExists()
        if (System.getProperty("imab.screenshots") != null) {
            val out = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(onAllNodes(isDialog()).onLast().captureToImage().toAwtImage(), "png", File(out, "dialog-merge-selected.png"))
        }
        onNodeWithText("Merge").performClick()
        waitForIdle()

        val left = b.data.transactionsByAccount["chk"].orEmpty().filter { it.amount == -2500L }
        assertEquals(listOf("a"), left.map { it.id })
        assertTrue(left.single().merged)
        onNodeWithText("CORNER BAKERY 0042").assertDoesNotExist()
        onNodeWithText("Merge duplicates").assertExists()
    }

    @Test
    fun longPressStartsPickingAndTheSearchBarStaysPut() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        store.setEncryptionReminder(false)
        val b = store.budget
        b.saveAccount(Account("chk", "Everyday Checking"), 50000, LocalDate(2026, 9, 1))
        for (d in 1..28) b.saveTransaction(Transaction("t$d", "chk", LocalDate(2026, 10, d), -100L * d, "Shop $d"))
        val nav = NavState().apply { openAccount("chk") }
        setContent { App(store, NoPickers, nav, showSplash = false) }
        waitForIdle()

        onNodeWithText("Shop 28").performTouchInput { longClick() }
        waitForIdle()
        onNodeWithText("1 selected · pick at least one more").assertExists()

        // Scrolling down takes the buttons off screen but keeps the balances and the search bar.
        onNode(hasScrollToIndexAction()).performScrollToIndex(25)
        waitForIdle()
        onNodeWithText("Import bank file").assertDoesNotExist()
        onNodeWithText("Working balance").assertIsDisplayed()
        onNodeWithText("Search payee, category, memo, amount").assertIsDisplayed()
    }
}
