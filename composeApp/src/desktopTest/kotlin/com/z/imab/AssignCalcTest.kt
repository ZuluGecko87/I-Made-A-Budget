package com.z.imab

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import com.z.imab.budget.BudgetMath
import com.z.imab.data.BudgetStore
import com.z.imab.model.Account
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import com.z.imab.ui.AssignDialog
import com.z.imab.ui.LightColors
import com.z.imab.ui.today
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Typing a calculation into the Assigned field of a category's Assign dialog. */
@OptIn(ExperimentalTestApi::class)
class AssignCalcTest {
    private class MemStorage : Storage {
        val files = HashMap<String, String>()
        override val defaultDeviceName = "Test"
        override suspend fun readLocal(name: String) = files[name]
        override suspend fun writeLocal(name: String, text: String) { files[name] = text }
        override suspend fun readSync(location: SyncLocation) = listOf("")
        override suspend fun writeSync(location: SyncLocation, text: String) {}
    }

    @Test
    fun plusFiveAddsToWhatIsAssigned() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        val b = store.budget
        val month = YearMonth.of(today())
        b.saveAccount(Account("chk", "Checking"), 0, month.firstDay)
        b.saveTransaction(Transaction("pay", "chk", month.firstDay, 100000, "Payroll", categoryId = READY_TO_ASSIGN, cleared = true))
        b.setAssigned(month, "seed-groceries", 12000)
        val data = b.data
        val mb = BudgetMath.compute(data, month)
        var saved: Long? = null
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                AssignDialog(
                    data = data, mb = mb, categoryId = "seed-groceries", name = "Groceries", month = month,
                    lastMonth = BudgetMath.compute(data, month - 1).category("seed-groceries"),
                    free = BudgetMath.freeToAssign(data, month, month), coverOverspending = false,
                    onDismiss = {}, onSave = { amount, _, _ -> saved = amount }, onMove = { _, _, _ -> },
                )
            }
        }
        onNode(hasSetTextAction() and androidx.compose.ui.test.hasText("120.00")).performTextReplacement("120.00 + 5.00")
        waitForIdle()
        onNodeWithText("= $125.00").assertExists()
        onNodeWithText("Takes $5.00 from Ready to Assign.").assertExists()
        System.getProperty("imab.screenshots")?.let {
            val out = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(onNode(isDialog()).captureToImage().toAwtImage(), "png", File(out, "dialog-assign-calc.png"))
        }
        onNodeWithText("Save").performClick()
        assertEquals(12500L, saved)
    }
}
