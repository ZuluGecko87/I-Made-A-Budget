package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.budget.MonthBudget
import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.PREEXISTING_DEBT
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import com.z.imab.model.paymentCategoryId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CreditCardTest {
    private var now = 1_000L
    private val oct = YearMonth(2026, 10)
    private val nov = YearMonth(2026, 11)
    private val groceries = "seed-groceries"
    private val dining = "seed-dining-out"
    private val visaPay = paymentCategoryId("visa")

    /** $1,000 in checking, a Visa with no balance, $100 assigned to groceries in October. */
    private fun setup(): Budget = Budget("dev") { now++ }.also { b ->
        b.seedDefaults()
        b.saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 9, 30))
        b.saveAccount(Account("visa", "Visa", AccountType.CREDIT_CARD))
        b.setAssigned(oct, groceries, 10000)
    }

    private fun Budget.spend(id: String, account: String, day: Int, cents: Long, category: String?, month: Int = 10) =
        saveTransaction(Transaction(id, account, LocalDate(2026, month, day), -cents, "Store", categoryId = category))

    private fun Budget.payCard(id: String, day: Int, cents: Long, month: Int = 10) =
        saveTransaction(Transaction(id, "chk", LocalDate(2026, month, day), -cents, transferAccountId = "visa"))

    private fun Budget.month(m: YearMonth): MonthBudget = BudgetMath.compute(data, m)

    /** Money in the budget must equal cash in budget accounts minus card debt nobody budgeted for. */
    private fun assertBalanced(b: Budget, m: YearMonth) {
        val mb = b.month(m)
        val cash = b.data.accounts.values.filter { it.onBudget && !it.type.isCredit }.sumOf { b.data.balance(it.id) }
        val budgeted = mb.readyToAssign + mb.categories.values.sumOf { maxOf(0, it.available) } -
            mb.categories.values.sumOf { it.cashOverspent }
        assertEquals(cash, budgeted, "budget doesn't add up to cash on hand in $m")
    }

    @Test
    fun cardSpendingMovesMoneyToPaymentCategory() {
        val b = setup()
        b.spend("s", "visa", 3, 4000, groceries)
        val m = b.month(oct)
        assertEquals(6000L, m.category(groceries).available)
        assertEquals(-4000L, m.category(groceries).activity)
        assertEquals(4000L, m.category(visaPay).available)
        assertEquals(4000L, m.category(visaPay).activity)
        assertEquals(90000L, m.readyToAssign)
        assertEquals(0L, m.cards.single().underfunded)
        assertBalanced(b, oct)
    }

    @Test
    fun payingTheCardUsesThePaymentCategory() {
        val b = setup()
        b.spend("s", "visa", 3, 4000, groceries)
        b.payCard("p", 20, 4000)
        val m = b.month(oct)
        assertEquals(0L, m.category(visaPay).available)
        assertEquals(0L, m.cards.single().owed)
        assertEquals(90000L, m.readyToAssign, "a card payment doesn't change Ready to Assign")
        assertEquals(0, b.data.uncategorizedCount)
        assertBalanced(b, oct)
    }

    @Test
    fun creditOverspendingBecomesDebtNotABudgetHit() {
        val b = setup()
        b.spend("s", "visa", 3, 15000, groceries) // $150 against $100 assigned
        val m = b.month(oct)
        assertEquals(-5000L, m.category(groceries).available)
        assertEquals(5000L, m.category(groceries).creditOverspent)
        assertEquals(0L, m.category(groceries).cashOverspent)
        assertEquals(10000L, m.category(visaPay).available, "only the covered $100 moves")
        assertEquals(5000L, m.cards.single().underfunded)

        val n = b.month(nov)
        assertEquals(0L, n.category(groceries).available, "overspending resets next month")
        assertEquals(90000L, n.readyToAssign, "credit overspending isn't taken from next month")
        assertEquals(10000L, n.category(visaPay).available)
        assertBalanced(b, oct)
        assertBalanced(b, nov)
    }

    @Test
    fun coveringCreditOverspendingInTheSameMonthFundsThePayment() {
        val b = setup()
        b.spend("s", "visa", 3, 15000, groceries)
        b.setAssigned(oct, groceries, 15000)
        val m = b.month(oct)
        assertEquals(0L, m.category(groceries).available)
        assertEquals(15000L, m.category(visaPay).available)
        assertEquals(0L, m.cards.single().underfunded)
    }

    @Test
    fun cashOverspendingComesOutOfNextMonth() {
        val b = setup()
        b.spend("s", "chk", 3, 15000, groceries)
        val m = b.month(oct)
        assertEquals(5000L, m.category(groceries).cashOverspent)
        assertEquals(90000L, m.readyToAssign)
        val n = b.month(nov)
        assertEquals(85000L, n.readyToAssign)
        assertEquals(5000L, n.overspentLastMonth)
        assertBalanced(b, nov)
    }

    @Test
    fun mixedCashAndCardOverspendingCountsCardFirst() {
        val b = setup()
        b.spend("c", "chk", 2, 8000, groceries)
        b.spend("v", "visa", 3, 5000, groceries) // $130 against $100: $30 short
        val m = b.month(oct)
        assertEquals(3000L, m.category(groceries).creditOverspent)
        assertEquals(2000L, m.category(visaPay).available)
        assertEquals(90000L, b.month(nov).readyToAssign)
    }

    @Test
    fun refundOnTheCardMovesMoneyBack() {
        val b = setup()
        b.spend("s", "visa", 3, 4000, groceries)
        b.saveTransaction(Transaction("r", "visa", LocalDate(2026, 10, 9), 1500, "Store refund", categoryId = groceries))
        val m = b.month(oct)
        assertEquals(7500L, m.category(groceries).available)
        assertEquals(2500L, m.category(visaPay).available)
        assertEquals(2500L, m.cards.single().owed)
        assertBalanced(b, oct)
    }

    @Test
    fun preexistingDebtIsPaidByAssigningToThePaymentCategory() {
        val b = Budget("dev") { now++ }.also { it.seedDefaults() }
        b.saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 9, 30))
        b.saveAccount(Account("visa", "Visa", AccountType.CREDIT_CARD), -30000, LocalDate(2026, 9, 30))
        val start = b.data.transactions.values.single { it.accountId == "visa" }
        assertEquals(PREEXISTING_DEBT, start.categoryId)
        assertEquals(0, b.data.uncategorizedCount)

        var m = b.month(oct)
        assertEquals(100000L, m.readyToAssign, "old debt doesn't reduce Ready to Assign")
        assertEquals(30000L, m.cards.single().underfunded)

        b.setAssigned(oct, visaPay, 30000)
        b.payCard("p", 15, 30000)
        m = b.month(oct)
        assertEquals(70000L, m.readyToAssign)
        assertEquals(0L, m.category(visaPay).available)
        assertEquals(0L, m.cards.single().owed)
        assertBalanced(b, oct)
    }

    @Test
    fun payingOldDebtWithoutBudgetingItIsCashOverspending() {
        val b = Budget("dev") { now++ }.also { it.seedDefaults() }
        b.saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 9, 30))
        b.saveAccount(Account("visa", "Visa", AccountType.CREDIT_CARD), -30000, LocalDate(2026, 9, 30))
        b.payCard("p", 15, 30000)
        assertEquals(-30000L, b.month(oct).category(visaPay).available)
        assertEquals(70000L, b.month(nov).readyToAssign)
        assertBalanced(b, nov)
    }

    @Test
    fun cashAdvanceAddsToThePaymentCategory() {
        val b = setup()
        b.saveTransaction(Transaction("adv", "visa", LocalDate(2026, 10, 5), -20000, transferAccountId = "chk"))
        val m = b.month(oct)
        assertEquals(20000L, m.category(visaPay).available)
        assertEquals(0L, m.cards.single().underfunded)
        assertEquals(90000L, m.readyToAssign)
        assertBalanced(b, oct)
    }

    @Test
    fun balanceTransferMovesMoneyBetweenCards() {
        val b = setup()
        b.saveAccount(Account("amex", "Amex", AccountType.CREDIT_CARD))
        b.setAssigned(oct, dining, 5000)
        b.spend("d", "amex", 4, 5000, dining)
        // Move the Amex balance onto the Visa.
        b.saveTransaction(Transaction("bt", "visa", LocalDate(2026, 10, 10), -5000, transferAccountId = "amex"))
        val m = b.month(oct)
        assertEquals(0L, m.category(paymentCategoryId("amex")).available)
        assertEquals(5000L, m.category(visaPay).available)
        assertEquals(5000L, m.cards.first { it.accountId == "visa" }.owed)
        assertBalanced(b, oct)
    }

    @Test
    fun uncategorizedSpendingAndRewardsOnTheCardStayBalanced() {
        val b = setup()
        b.spend("u", "visa", 3, 1999, null)
        var m = b.month(oct)
        assertEquals(88001L, m.readyToAssign)
        assertEquals(1999L, m.category(visaPay).available)
        assertBalanced(b, oct)

        b.saveTransaction(Transaction("cb", "visa", LocalDate(2026, 10, 9), 500, "Cashback", categoryId = READY_TO_ASSIGN))
        m = b.month(oct)
        assertEquals(88501L, m.readyToAssign)
        assertEquals(1499L, m.category(visaPay).available)
        assertEquals(0L, m.cards.single().underfunded)
        assertBalanced(b, oct)
    }

    @Test
    fun trackingCardHasNoPaymentCategory() {
        val b = setup()
        b.saveAccount(Account("store", "Store card", AccountType.CREDIT_CARD, onBudget = false))
        assertEquals(listOf("visa"), b.month(oct).cards.map { it.accountId })
    }
}
