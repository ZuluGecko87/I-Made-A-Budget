package com.z.imab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.z.imab.data.BudgetStore
import com.z.imab.model.Account
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import com.z.imab.ui.LightColors
import com.z.imab.ui.ReportsScreen
import com.z.imab.ui.today
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Choosing the time a report covers, on a phone-width screen. */
@OptIn(ExperimentalTestApi::class)
class ReportsPeriodUiTest {
    private class MemStorage : Storage {
        val files = HashMap<String, String>()
        override val defaultDeviceName = "Test"
        override suspend fun readLocal(name: String) = files[name]
        override suspend fun writeLocal(name: String, text: String) { files[name] = text }
        override suspend fun readSync(location: SyncLocation) = listOf("")
        override suspend fun writeSync(location: SyncLocation, text: String) {}
    }

    @Test
    fun lastThreeMonthsAndACustomRangeBackToTheStart() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        val now = YearMonth.of(today())
        val start = now - 20
        val b = store.budget
        b.saveAccount(Account("chk", "Checking"), 1000000, start.firstDay)
        var m = start
        var n = 1
        while (m <= now) {
            b.saveTransaction(Transaction("g$m", "chk", m.firstDay, -1000L * n++, "Store", categoryId = "seed-groceries"))
            m += 1
        }
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                Surface { Box(Modifier.width(400.dp).fillMaxHeight()) { ReportsScreen(store) } }
            }
        }
        fun shot(name: String) {
            if (System.getProperty("imab.screenshots") == null) return
            val out = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        }

        fun period(name: String) {
            onNodeWithText("Period").performClick()
            waitForIdle()
            onAllNodesWithText(name).onLast().performClick()
            waitForIdle()
        }
        period("Last 3 months")
        val m3 = now - 2
        onNodeWithText("${m3.label.take(3)} ${m3.year} – ${now.label.take(3)} ${now.year}").assertExists()

        // Custom starts at the month the budget started and runs to this month.
        period("Custom")
        onNodeWithText("${start.label.take(3)} ${start.year}").assertExists()
        onNodeWithText("21 months").assertExists()
        shot("reports-custom-all")

        // Pick a later start month.
        val from = now - 5
        onNodeWithText("From").performClick()
        waitForIdle()
        if (onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()) onAllNodes(hasSetTextAction()).onLast().performTextInput("${from.label.take(3)} ${from.year}")
        onAllNodesWithText("${from.label.take(3)} ${from.year}").onLast().performClick()
        waitForIdle()
        onNodeWithText("6 months").assertExists()
        shot("reports-custom-6")

        onNodeWithText("Since the budget started").performClick()
        waitForIdle()
        onNodeWithText("21 months").assertExists()
    }
}
