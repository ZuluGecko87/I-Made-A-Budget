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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
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
        b.saveScheduled(ScheduledTransaction("r", "chk", month.lastDay, month.lastDay, Frequency.MONTHLY, -150000, "Landlord", categoryId = "seed-rent-mortgage"))

        val nav = NavState()
        val out = File("build/screenshots").apply { mkdirs() }
        fun shot(name: String, w: Int, h: Int) {
            val scene = ImageComposeScene(w, h, Density(1.5f)) { App(store, NoPickers, nav) }
            scene.render(0)
            val img = scene.render(1_000_000_000)
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        shot("budget-wide", 1800, 1200)
        shot("budget-phone", 620, 1300)
        nav.openAccount("chk"); shot("register-wide", 1800, 1200)
        nav.openAccount("cc"); shot("register-card-wide", 1800, 1000)
        nav.go(Screen.Accounts); shot("accounts-phone", 620, 1300)
        nav.go(Screen.Scheduled); shot("scheduled-wide", 1800, 1000)
        nav.go(Screen.Settings); shot("settings-wide", 1800, 1000)
        scope.cancel()
    }
}
