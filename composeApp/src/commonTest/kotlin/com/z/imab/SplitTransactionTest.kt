package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.budget.ReportPeriod
import com.z.imab.budget.Reports
import com.z.imab.data.Budget
import com.z.imab.data.ImabJson
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.SplitLine
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import com.z.imab.model.paymentCategoryId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One transaction whose amount is shared between several categories. */
class SplitTransactionTest {
    private var now = 1_000L
    private val oct = YearMonth(2026, 10)
    private val groceries = "seed-groceries"
    private val household = "seed-household"
    private val dining = "seed-dining-out"

    private fun setup() = Budget("dev") { now++ }.also { b ->
        b.seedDefaults()
        b.saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 9, 30))
        b.setAssigned(oct, groceries, 20000)
        b.setAssigned(oct, household, 5000)
    }

    /** A $100 store run: $70 groceries, $30 household. */
    private val storeRun = Transaction(
        "s", "chk", LocalDate(2026, 10, 5), -10000, "Big Box",
        splits = listOf(SplitLine(groceries, -7000), SplitLine(household, -3000)),
    )

    @Test
    fun eachCategoryGetsItsShare() {
        val b = setup()
        b.saveTransaction(storeRun)
        val mb = BudgetMath.compute(b.data, oct)
        assertEquals(-7000, mb.category(groceries).activity)
        assertEquals(13000, mb.category(groceries).available)
        assertEquals(-3000, mb.category(household).activity)
        assertEquals(2000, mb.category(household).available)
        assertEquals(90000, b.data.balance("chk"), "the account sees one $100 transaction")
        assertFalse(b.data.needsCategory(b.data.transactions.getValue("s")))
        assertEquals("Split: Groceries, Household", b.data.categoryLabel(b.data.transactions.getValue("s")))
        assertNull(b.data.transactions.getValue("s").categoryId)
    }

    @Test
    fun linesMustAddUpToTheTotal() {
        val b = setup()
        assertFailsWith<IllegalArgumentException> {
            b.saveTransaction(storeRun.copy(splits = listOf(SplitLine(groceries, -7000), SplitLine(household, -2000))))
        }
        assertFalse("s" in b.data.transactions)
    }

    @Test
    fun aSingleLineIsJustACategory() {
        val b = setup()
        b.saveTransaction(storeRun.copy(splits = listOf(SplitLine(dining, -10000))))
        val t = b.data.transactions.getValue("s")
        assertEquals(dining, t.categoryId)
        assertFalse(t.isSplit)
    }

    @Test
    fun anUncategorizedLineStillNeedsACategory() {
        val b = setup()
        b.saveTransaction(storeRun.copy(splits = listOf(SplitLine(groceries, -7000), SplitLine(null, -3000))))
        assertTrue(b.data.needsCategory(b.data.transactions.getValue("s")))
        assertEquals(1, b.data.uncategorizedCount)
    }

    @Test
    fun deletingACategoryLeavesItsLineUncategorized() {
        val b = setup()
        b.saveTransaction(storeRun)
        b.deleteCategory(household)
        val t = b.data.transactions.getValue("s")
        assertEquals(listOf(SplitLine(groceries, -7000), SplitLine(null, -3000)), t.splits)
        assertTrue(b.data.needsCategory(t))
    }

    @Test
    fun cardSpendingMovesEachShareToThePayment() {
        val b = setup()
        b.saveAccount(Account("visa", "Visa", AccountType.CREDIT_CARD))
        b.saveTransaction(storeRun.copy(accountId = "visa"))
        val mb = BudgetMath.compute(b.data, oct)
        // Both shares were funded, so the whole $100 is set aside for the card.
        assertEquals(10000, mb.category(paymentCategoryId("visa")).available)
        assertEquals(13000, mb.category(groceries).available)
        assertEquals(2000, mb.category(household).available)
    }

    @Test
    fun reportsCountEachLine() {
        val b = setup()
        b.saveTransaction(storeRun)
        // A $500 deposit: $450 pay, $50 refund back to groceries.
        b.saveTransaction(
            Transaction(
                "p", "chk", LocalDate(2026, 10, 10), 50000, "Employer",
                splits = listOf(SplitLine(READY_TO_ASSIGN, 45000), SplitLine(groceries, 5000)),
            )
        )
        val spending = Reports.spending(b.data, ReportPeriod.month(oct))
        assertEquals(5000, spending.total) // 70 + 30 - 50 refund
        assertEquals(2000, spending.byCategory.single { it.id == groceries }.amount)
        assertEquals(3000, spending.byCategory.single { it.id == household }.amount)
        assertEquals(45000, Reports.income(b.data, ReportPeriod.month(oct)).total)
        assertEquals(45000, BudgetMath.compute(b.data, oct).incomeThisMonth)
    }

    @Test
    fun mergingKeepsTheSplitYouEntered() {
        val b = setup()
        b.saveTransaction(storeRun)
        b.saveTransaction(Transaction("bank", "chk", LocalDate(2026, 10, 6), -10000, "BIG BOX #12", categoryId = dining, importId = "F1"))
        b.mergeTransactions("s", "bank")
        assertFalse("bank" in b.data.transactions)
        val t = b.data.transactions.getValue("s")
        assertEquals(storeRun.splits, t.splits)
        assertNull(t.categoryId)
        assertTrue(t.merged)
    }

    @Test
    fun transfersAreNeverSplit() {
        val b = setup()
        b.saveAccount(Account("sav", "Savings"))
        b.saveTransaction(storeRun.copy(transferAccountId = "sav"))
        assertFalse(b.data.transactions.getValue("s").isSplit)
    }

    @Test
    fun splitsSurviveSavingAndOlderFilesHaveNone() {
        val json = ImabJson.encodeToString(Transaction.serializer(), storeRun)
        assertEquals(storeRun, ImabJson.decodeFromString(Transaction.serializer(), json))
        val old = """{"id":"o","accountId":"chk","date":"2026-10-01","amount":-500,"categoryId":"$groceries"}"""
        assertEquals(emptyList(), ImabJson.decodeFromString(Transaction.serializer(), old).splits)
    }
}
