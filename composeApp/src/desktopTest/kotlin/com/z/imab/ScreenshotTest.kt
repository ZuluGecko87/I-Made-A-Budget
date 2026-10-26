package com.z.imab

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.z.imab.data.BudgetStore
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.OfxParser
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.Frequency
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.ScheduledTransaction
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import com.z.imab.platform.PickedFile
import com.z.imab.platform.Pickers
import com.z.imab.platform.Storage
import com.z.imab.platform.SyncLocation
import com.z.imab.ui.App
import com.z.imab.ui.NavState
import com.z.imab.ui.Screen
import com.z.imab.ui.today
import com.z.imab.ui.drawAppIcon
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Renders each screen with sample data to composeApp/build/screenshots (run with -Pscreenshots). */
class ScreenshotTest {
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
        override val syncHint = "hint"
        override suspend fun saveFile(suggestedName: String, text: String): String? = null
        override suspend fun pickArchive(): PickedFile? = null
    }

    @Test
    fun renderScreens() {
        if (System.getProperty("imab.screenshots") == null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = BudgetStore(MemStorage(), scope)
        runBlocking { store.start() }
        val b = store.budget
        val t = today()
        val month = YearMonth.of(t)
        b.saveAccount(Account("chk", "Everyday Checking"), 120000, month.firstDay.minus(1, DateTimeUnit.DAY))
        b.saveAccount(Account("sav", "Savings", AccountType.SAVINGS), 500000, month.firstDay.minus(1, DateTimeUnit.DAY))
        b.saveAccount(Account("cc", "Visa", AccountType.CREDIT_CARD), -32050, month.firstDay.minus(1, DateTimeUnit.DAY))
        b.saveTransaction(Transaction("p", "chk", month.firstDay, 310000, "Acme Payroll", categoryId = READY_TO_ASSIGN, cleared = true))
        b.saveTransaction(Transaction("g", "chk", month.firstDay, -8423, "Kroger", categoryId = "seed-groceries", cleared = true))
        b.saveTransaction(Transaction("d", "cc", month.firstDay, -4210, "Chipotle", categoryId = "seed-dining-out"))
        b.saveTransaction(Transaction("u", "cc", month.firstDay, -1999, "NETFLIX.COM"))
        b.saveTransaction(Transaction("cg", "cc", month.firstDay, -6315, "Costco", categoryId = "seed-groceries"))
        b.setAssigned(month, com.z.imab.model.paymentCategoryId("cc"), 10000)
        b.saveTransaction(Transaction("x", "chk", month.firstDay, -20000, transferAccountId = "sav"))
        b.setAssigned(month, "seed-groceries", 60000)
        b.setAssigned(month, "seed-rent-mortgage", 150000)
        b.setAssigned(month, "seed-dining-out", 3000)
        // Savings goals: one with a planned monthly amount, one with a target date.
        b.saveCategory(b.data.categories.getValue("seed-emergency-fund").copy(goal = com.z.imab.model.CategoryGoal(500000, monthly = 25000)))
        b.setAssigned(month - 1, "seed-emergency-fund", 50000)
        b.setAssigned(month, "seed-emergency-fund", 25000)
        b.saveCategory(b.data.categories.getValue("seed-vacation").copy(goal = com.z.imab.model.CategoryGoal(200000, targetDate = month.plus(9).firstDay)))
        b.setAssigned(month, "seed-vacation", 10000)
        // Monthly spending goals: rent rolls over, dining out resets each month.
        b.saveCategory(b.data.categories.getValue("seed-rent-mortgage").copy(goal = com.z.imab.model.CategoryGoal(150000, type = com.z.imab.model.GoalType.MONTHLY_SPENDING)))
        b.saveCategory(b.data.categories.getValue("seed-dining-out").copy(goal = com.z.imab.model.CategoryGoal(20000, type = com.z.imab.model.GoalType.MONTHLY_SPENDING, rollover = false)))
        b.saveCategory(b.data.categories.getValue("seed-electric").copy(goal = com.z.imab.model.CategoryGoal(12000, type = com.z.imab.model.GoalType.MONTHLY_SPENDING)))
        b.saveScheduled(ScheduledTransaction("r", "chk", month.lastDay, month.lastDay, Frequency.MONTHLY, -150000, "Landlord", categoryId = "seed-rent-mortgage"))

        val nav = NavState()
        val out = File("build/screenshots").apply { mkdirs() }
        fun shot(name: String, w: Int, h: Int, splash: Boolean = false) {
            val scene = ImageComposeScene(w, h, Density(1.5f)) { App(store, NoPickers, nav, showSplash = splash) }
            // Step through frames so animations (splash fade-in, money rain) actually run.
            for (ms in 0L until 1_400L step 100L) scene.render(ms * 1_000_000)
            val img = scene.render(1_400_000_000)
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        store.setTheme("light")
        shot("splash-phone", 620, 1300, splash = true)
        shot("splash-wide", 1800, 1100, splash = true)
        shot("budget-wide", 1800, 1200)
        shot("budget-phone", 620, 1300)
        shot("budget-phone-tall", 620, 2400)
        nav.openAccount("chk"); shot("register-wide", 1800, 1200)
        nav.openAccount("cc"); shot("register-card-wide", 1800, 1000)
        nav.go(Screen.Accounts); shot("accounts-phone", 620, 1300)
        nav.go(Screen.Scheduled); shot("scheduled-wide", 1800, 1000)
        nav.go(Screen.Settings); shot("settings-wide", 1800, 1000)
        nav.go(Screen.Budget); shot("budget-goals-wide", 1800, 2700)
        nav.go(Screen.Accounts); shot("accounts-wide", 1800, 900)
        b.saveCombinedGoal(com.z.imab.model.CombinedGoal("house", "House", listOf("seed-emergency-fund", "seed-car-replacement", "seed-vacation"), 2_500_000, targetDate = month.plus(24).firstDay))
        nav.go(Screen.Goals); shot("goals-wide", 1800, 1500); shot("goals-phone", 620, 1600)

        // Dropping a bank file on Everyday Checking: first import into it.
        val qbo = PickedFile("Checking_Oct.qbo", Samples.QBO_CHECKING)
        nav.openAccount("chk"); nav.importFile(qbo); shot("import-first-wide", 1800, 1500)
        // Import it, then drop the same file again: only since the last import, with a duplicate alert.
        val stmt = com.z.imab.importer.OfxParser.parse(Samples.QBO_CHECKING).single()
        b.applyImport("chk", stmt.accountKey, com.z.imab.importer.ImportMatcher.analyze(stmt.transactions, "chk", b.data))
        nav.openAccount("chk"); nav.importFile(qbo); shot("import-again-wide", 1800, 1100)

        // Something entered by hand that the bank also sent (added as new instead of merged).
        b.saveTransaction(Transaction("hand", "chk", month.firstDay.plus(1, DateTimeUnit.DAY), -6789, "Target", "school supplies", categoryId = "seed-shopping"))
        b.applyImport(
            "chk", null,
            listOf(com.z.imab.importer.OfxTransaction("T1", "DEBIT", month.firstDay.plus(3, DateTimeUnit.DAY), -6789, "TARGET 00012345", "", null))
                .let { com.z.imab.importer.ImportMatcher.analyze(it, "chk", b.data) }
                .map { it.copy(action = com.z.imab.importer.ImportAction.ADD) },
        )
        nav.openAccount("chk"); shot("register-merge-wide", 1800, 1100)
        nav.go(Screen.Accounts); shot("accounts-merge-phone", 620, 900)
        // And the import preview when the bank file arrives after you entered it.
        b.saveTransaction(Transaction("hand2", "chk", month.firstDay.plus(10, DateTimeUnit.DAY), -2500, "Gas", categoryId = "seed-transportation"))
        nav.openAccount("chk")
        nav.importFile(PickedFile("gas.qbo", Samples.QBO_CHECKING.replace("<TRNAMT>-1200.00", "<TRNAMT>-25.00").replace("20261005", "20261012").replace("20261005001", "GAS1")))
        shot("import-merge-wide", 1800, 1300)

        // Mortgage: a loan account with terms, then a monthly payment arriving from the bank.
        b.saveAccount(
            Account("home", "Home Loan", AccountType.LOAN, onBudget = false, loan = com.z.imab.model.LoanTerms(6.0, 40000, 250000, extraPrincipal = 50000)),
            -30_000_000, month.firstDay.minus(1, DateTimeUnit.DAY),
        )
        val mortgageQbo = PickedFile(
            "mortgage.qbo",
            Samples.QBO_CHECKING.replace("<NAME>ONLINE TRANSFER TO SAVINGS", "<NAME>MAIN STREET MORTGAGE 0042")
                .replace("<TRNAMT>-1200.00", "<TRNAMT>-2500.00").replace("<DTPOSTED>20261005", "<DTPOSTED>20261015")
                .replace("<FITID>20261005001", "<FITID>MORT10"),
        )
        nav.openAccount("chk"); nav.importFile(mortgageQbo); shot("import-mortgage-norule-wide", 1800, 1100)
        b.saveRule(com.z.imab.model.PayeeRule("r1", "main street mortgage", categoryId = "seed-rent-mortgage", loanAccountId = "home"))
        nav.openAccount("chk"); nav.importFile(mortgageQbo); shot("import-mortgage-rule-wide", 1800, 1100)
        val mstmt = com.z.imab.importer.OfxParser.parse(mortgageQbo.text).single()
        b.applyImport("chk", null, com.z.imab.importer.ImportMatcher.analyze(mstmt.transactions.filter { it.fitId == "MORT10" }, "chk", b.data))
        nav.openAccount("home"); shot("loan-register-wide", 1800, 900)
        nav.go(Screen.Settings); shot("settings-rules-wide", 1800, 3600)

        // The account form for a new mortgage and for editing the existing loan.
        fun dialogShot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
            val scene = ImageComposeScene(1100, 1500, Density(1.5f)) {
                androidx.compose.material3.MaterialTheme(colorScheme = com.z.imab.ui.LightColors) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize()) {}
                    content()
                }
            }
            for (ms in 0L until 600L step 100L) scene.render(ms * 1_000_000)
            File(out, "$name.png").writeBytes(scene.render(600_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        dialogShot("dialog-new-mortgage") {
            com.z.imab.ui.AccountDialog(null, { "x" }, suggestedName = "House", suggestedType = AccountType.MORTGAGE, onSave = { _, _, _ -> }, onDelete = null, onDismiss = {})
        }
        dialogShot("dialog-payee-suggest") {
            com.z.imab.ui.TransactionDialog(
                b.data, Transaction("draft", "chk", today(), -1000, "Kr"), "chk", { "x" },
                onSave = {}, onDelete = null, onDismiss = {},
            )
        }
        run {
            // Click the payee field's arrow to open its suggestions.
            val scene = ImageComposeScene(1100, 1500, Density(1.5f)) {
                androidx.compose.material3.MaterialTheme(colorScheme = com.z.imab.ui.LightColors) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize()) {}
                    com.z.imab.ui.TransactionDialog(b.data, Transaction("draft", "chk", today(), -1000, "Kr"), "chk", { "x" }, onSave = {}, onDelete = null, onDismiss = {})
                }
            }
            for (ms in 0L until 400L step 100L) scene.render(ms * 1_000_000)
            val arrow = androidx.compose.ui.geometry.Offset(898f / 1.5f * 1.5f, 528f)
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            fun click(at: androidx.compose.ui.geometry.Offset) {
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at)
            }
            click(arrow)
            for (ms in 400L until 900L step 100L) scene.render(ms * 1_000_000)
            File(out, "dialog-payee-open.png").writeBytes(scene.render(900_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        // Reconcile: Everyday Checking with the statement $5 off, then matching.
        val chkCleared = b.data.transactionsByAccount["chk"].orEmpty().filter { it.cleared && it.date <= today() }.sumOf { it.amount }
        dialogShot("dialog-reconcile-off") {
            com.z.imab.ui.ReconcileDialog(b.data, b.data.accounts.getValue("chk"), { _, _ -> }, { _, _, _ -> }, {}, initialBalanceText = com.z.imab.model.Money.toInput(chkCleared - 500))
        }
        dialogShot("dialog-reconcile-match") {
            com.z.imab.ui.ReconcileDialog(b.data, b.data.accounts.getValue("chk"), { _, _ -> }, { _, _, _ -> }, {}, initialBalanceText = com.z.imab.model.Money.toInput(chkCleared))
        }
        b.reconcile("chk", today(), chkCleared, adjust = false)
        nav.openAccount("chk"); shot("register-reconciled-wide", 1800, 1100)
                dialogShot("dialog-edit-loan") {
            com.z.imab.ui.AccountDialog(b.data.accounts.getValue("home"), { "x" }, currentBalance = b.data.balance("home"), onSave = { _, _, _ -> }, onDelete = {}, onDismiss = {})
        }
        store.setTheme("dark")
        nav.go(Screen.Budget); shot("budget-dark-wide", 1800, 1200)
        shot("splash-dark-phone", 620, 1300, splash = true)
        scope.cancel()
    }

    /**
     * Regenerates the app icons from the same artwork as the splash screen
     * (run with -Picons): composeApp/icons/IMAB-1024.png for the Mac .icns, and the Android
     * adaptive icon foreground.
     */
    @Test
    fun renderIcons() {
        if (System.getProperty("imab.icons") == null) return
        fun png(file: File, px: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
            val scene = ImageComposeScene(px, px, Density(1f), content = content)
            file.parentFile.mkdirs()
            file.writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        // macOS icons leave a transparent margin around the rounded tile.
        png(File("icons/IMAB-1024.png"), 1024) {
            Canvas(Modifier.fillMaxSize().padding(100.dp)) { drawAppIcon() }
        }
        // Android masks the adaptive foreground to the middle ~66%, so keep the art inside it.
        png(File("src/androidMain/res/mipmap-xxxhdpi/ic_launcher_foreground.png"), 432) {
            Canvas(Modifier.fillMaxSize().padding(88.dp)) { drawAppIcon(background = false) }
        }
    }
}
