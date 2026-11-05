package com.z.imab

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.data.BudgetStore
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.LoanTerms
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

/** Opening a mortgage's payoff planner and trying extra payments. */
@OptIn(ExperimentalTestApi::class)
class LoanPlannerUiTest {
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
    fun extraPaymentsShowWhatTheySaveAndCanBecomeThePlan() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        store.setEncryptionReminder(false)
        // $300,000 at 6%, $1,798.66 principal and interest + $400 escrow.
        store.budget.saveAccount(
            Account("home", "Home Loan", AccountType.MORTGAGE, onBudget = false, loan = LoanTerms(6.0, 40000, 219866)),
            -30_000_000, LocalDate(2026, 1, 1),
        )
        val nav = NavState().apply { openAccount("home") }
        setContent { App(store, NoPickers, nav, showSplash = false) }
        waitForIdle()

        onNodeWithText("Payoff planner & schedule").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Payoff planner").assertExists()
        onNodeWithText("Regular schedule").assertExists()
        onNodeWithText("Extra payments save").assertDoesNotExist()

        onNode(hasSetTextAction() and hasText("Extra each month")).performTextInput("200")
        waitForIdle()
        onNodeWithText("Extra payments save").assertExists()
        onNodeWithText("Schedule with extra payments").assertExists()

        onNodeWithText("Make the monthly extra my plan").performClick()
        waitForIdle()
        assertEquals(20000, store.budget.data.accounts.getValue("home").loan?.extraPrincipal)
        onNodeWithText("Make the monthly extra my plan").assertDoesNotExist()
    }
}
