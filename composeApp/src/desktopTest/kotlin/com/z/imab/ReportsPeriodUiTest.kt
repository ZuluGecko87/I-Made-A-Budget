package com.z.imab

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import com.z.imab.ui.DarkColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.z.imab.data.BudgetStore
import com.z.imab.model.Account
import com.z.imab.model.AccountType
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        onNodeWithText(
            if (m3.year == now.year) "${m3.label.take(3)} – ${now.label.take(3)} ${now.year}"
            else "${m3.label.take(3)} ${m3.year % 100} – ${now.label.take(3)} ${now.year % 100}",
        ).assertExists()

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

    @Test
    fun netWorthOverTime() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        val now = YearMonth.of(today())
        val start = now - 11
        val b = store.budget
        // Starts $3,000 under water and saves $500 a month.
        b.saveAccount(Account("chk", "Checking"), 200000, start.firstDay)
        b.saveAccount(Account("car", "Car loan", AccountType.LOAN), -500000, start.firstDay)
        var m = start + 1
        while (m <= now) {
            b.saveTransaction(Transaction("s$m", "chk", m.firstDay, 50000, "Paycheck", categoryId = "rta"))
            m += 1
        }
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                Surface { Box(Modifier.width(400.dp).fillMaxHeight()) { ReportsScreen(store) } }
            }
        }
        onNodeWithText("Net worth").performClick()
        waitForIdle()
        onNodeWithText("Period").performClick()
        waitForIdle()
        onAllNodesWithText("Last 12 months").onLast().performClick()
        waitForIdle()
        onNodeWithText("Net worth by month").assertExists()
        // -$3,000 + 11 × $500
        onAllNodesWithText("$2,500.00").onFirst().assertExists()
        onNodeWithText("Own $7,500.00 · owe $5,000.00").assertExists()
        if (System.getProperty("imab.screenshots") != null) {
            val out = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "reports-net-worth.png"))
        }
    }

    @Test
    fun filterSpendingToCategoriesAndShowItAsAPie() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        val now = YearMonth.of(today())
        val b = store.budget
        b.saveAccount(Account("chk", "Checking"), 1000000, now.firstDay)
        listOf(
            "seed-groceries" to 42000L, "seed-dining-out" to 18000L, "seed-electric" to 12000L, "seed-internet" to 8000L,
            "seed-entertainment" to 6000L, "seed-household" to 4000L, "seed-gifts" to 2000L,
        ).forEachIndexed { i, (cat, cents) ->
            b.saveTransaction(Transaction("t$i", "chk", now.firstDay, -cents, "Shop", categoryId = cat))
        }
        var dark by mutableStateOf(false)
        setContent {
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
                Surface { Box(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { Box(Modifier.height(1300.dp)) { ReportsScreen(store) } } }
            }
        }
        fun shot(name: String) {
            if (System.getProperty("imab.screenshots") == null) return
            val out = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        }

        onNodeWithText("All categories").assertExists()
        onNodeWithText("$920.00").assertExists()
        onNodeWithText("Pie").performClick()
        waitForIdle()
        // Five largest, then Other for the remaining two.
        onNodeWithText("Other (2)").assertExists()
        shot("reports-pie")
        dark = true
        waitForIdle()
        shot("reports-pie-dark")
        dark = false

        // Just groceries and dining out.
        onNodeWithText("All categories").performClick()
        waitForIdle()
        onNodeWithText("Clear").performClick()
        onAllNodesWithText("Groceries").onLast().performClick()
        onAllNodesWithText("Dining Out").onLast().performClick()
        onNodeWithText("Show 2").performClick()
        waitForIdle()
        onNodeWithText("2 categories").assertExists()
        onNodeWithText("Spent in these categories").assertExists()
        onNodeWithText("65% of $920.00").assertExists()
        onNodeWithText("Other (2)").assertDoesNotExist()
        shot("reports-pie-filtered")

        onNodeWithText("Show all").performClick()
        waitForIdle()
        onNodeWithText("All categories").assertExists()
    }

    @Test
    fun periodLabelsStayOnOneLineOnANarrowPhoneWithLargeText() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.3f)) {
                MaterialTheme(colorScheme = LightColors) {
                    Surface { Box(Modifier.width(360.dp).fillMaxHeight()) { ReportsScreen(store) } }
                }
            }
        }
        fun assertOneLine(text: String) {
            val h = onNodeWithText(text).fetchSemanticsNode().size.height
            val line = with(density) { (20 * 1.3f).sp.toPx() }
            assertTrue(h < line * 1.5f, "'$text' wraps: ${h}px tall, one line is ${line}px")
        }
        // Every month of the year, including the long names.
        var m = YearMonth.of(today())
        repeat(12) {
            assertOneLine("${m.label.take(3)} ${m.year}")
            onNodeWithContentDescription("Earlier").performClick()
            waitForIdle()
            m -= 1
        }
        if (System.getProperty("imab.screenshots") != null) {
            val out = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "reports-period-narrow.png"))
        }
        fun menuWidth() = onNodeWithText("Period").fetchSemanticsNode().boundsInRoot.width
        val width = menuWidth()
        for (mode in listOf("Last 3 months", "YTD", "Last 12 months", "Custom", "Month")) {
            onNodeWithText("Period").performClick()
            waitForIdle()
            onAllNodesWithText(mode).onLast().performClick()
            waitForIdle()
            assertEquals(width, menuWidth(), "the menu keeps its width for $mode")
            if (mode == "Last 12 months") {
                onNodeWithContentDescription("Earlier").performClick()
                waitForIdle()
                val to = m - 1 // stepped back 12 months above, and one more here
                val from = to - 11
                assertOneLine("${from.label.take(3)} ${from.year % 100} – ${to.label.take(3)} ${to.year % 100}")
            }
        }
    }

    @Test
    fun yearToDateIsThisYearOnlyAndTheMenuIsTheSameWidthOnAWideWindow() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                Surface { Box(Modifier.width(1200.dp).fillMaxHeight()) { ReportsScreen(store) } }
            }
        }
        val menu = onNodeWithText("Period").fetchSemanticsNode().boundsInRoot.width
        assertEquals(with(density) { 156.dp.toPx() }, menu, 0.5f, "a fixed width, not the window's")

        onNodeWithText("Period").performClick()
        waitForIdle()
        onAllNodesWithText("YTD").onLast().performClick()
        waitForIdle()
        val now = YearMonth.of(today())
        onNodeWithText(if (now.month == 1) "Jan ${now.year}" else "Jan – ${now.label.take(3)} ${now.year}").assertExists()
        onNodeWithContentDescription("Earlier").assertDoesNotExist()
        onNodeWithContentDescription("Later").assertDoesNotExist()

        onNodeWithText("Period").performClick()
        waitForIdle()
        onAllNodesWithText("Custom").onLast().performClick()
        waitForIdle()
        assertEquals(with(density) { 150.dp.toPx() }, onNodeWithText("From").fetchSemanticsNode().boundsInRoot.width, 0.5f)
        if (System.getProperty("imab.screenshots") != null) {
            val out = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "reports-wide-custom.png"))
        }
    }

    /** The user's phone: 1080px at 450dpi is 384dp wide, at normal text size. */
    @Test
    fun theRangeSitsBesideTheMenuOnTheUsersPhone() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                Surface { Box(Modifier.width(384.dp).fillMaxHeight()) { ReportsScreen(store) } }
            }
        }
        fun assertBeside(mode: String) {
            val menu = onNodeWithText("Period").fetchSemanticsNode().boundsInRoot
            val arrow = onNodeWithContentDescription("Later").fetchSemanticsNode().boundsInRoot
            assertTrue(arrow.center.y in menu.top..menu.bottom, "$mode: the range wrapped under the menu")
            assertTrue(arrow.right <= with(density) { 384.dp.toPx() }, "$mode: the range runs off the screen")
        }
        assertBeside("Month")
        for (mode in listOf("Last 3 months", "Last 12 months")) {
            onNodeWithText("Period").performClick()
            waitForIdle()
            onAllNodesWithText(mode).onLast().performClick()
            waitForIdle()
            assertBeside(mode)
            // Also across a year boundary, the longest label.
            repeat(11) { onNodeWithContentDescription("Earlier").performClick() }
            waitForIdle()
            assertBeside("$mode, a year back")
            if (System.getProperty("imab.screenshots") != null && mode == "Last 12 months") {
                val out = File("build/screenshots").apply { mkdirs() }
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "reports-period-384.png"))
            }
        }
    }

    /** Early in a month there may be spending in only one category: the pie is that one, whole. */
    @Test
    fun aPieWithOneCategoryIsStillAPie() = runComposeUiTest {
        val store = BudgetStore(MemStorage(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        runBlocking { store.start() }
        val now = YearMonth.of(today())
        store.budget.saveAccount(Account("chk", "Checking"), 1000000, now.firstDay)
        store.budget.saveTransaction(Transaction("r", "chk", now.firstDay, -150000, "Landlord", categoryId = "seed-rent-mortgage"))
        setContent {
            MaterialTheme(colorScheme = LightColors) {
                Surface { Box(Modifier.width(384.dp).fillMaxHeight()) { ReportsScreen(store) } }
            }
        }
        onNodeWithText("Pie").performClick()
        waitForIdle()
        onNodeWithText("100%").assertExists()
        onNodeWithText("Total").assertExists()
        if (System.getProperty("imab.screenshots") != null) {
            val out = File("build/screenshots").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "reports-pie-one.png"))
        }
    }
}
