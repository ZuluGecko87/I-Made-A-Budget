package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.data.Budget
import com.z.imab.data.DataBuilder
import com.z.imab.data.ImabJson
import com.z.imab.data.Kinds
import com.z.imab.data.Op
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.LoanSplit
import com.z.imab.model.LoanTerms
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LoanAccountTest {
    private val oct = YearMonth(2026, 10)

    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 1_000_000, LocalDate(2026, 9, 30))
    }

    @Test
    fun loanSavedAsBudgetAccountByOlderVersionDoesNotReduceReadyToAssign() {
        val b = budget()
        // What the previous version stored: an on-budget loan whose debt went to Ready to Assign.
        val legacy = Account("home", "Test loan", AccountType.LOAN, onBudget = true)
        b.merge(
            listOf(
                Op(Kinds.key(Kinds.ACCOUNT, "home"), 5_000, "old", ImabJson.encodeToJsonElement(Account.serializer(), legacy)),
                Op(
                    Kinds.key(Kinds.TXN, "debt"), 5_001, "old",
                    ImabJson.encodeToJsonElement(
                        Transaction.serializer(),
                        Transaction("debt", "home", LocalDate(2026, 9, 30), -30_000_000, "Starting Balance", categoryId = READY_TO_ASSIGN),
                    ),
                ),
            )
        )
        assertEquals(1_000_000L, BudgetMath.compute(b.data, oct).readyToAssign)
        assertEquals(0, b.data.uncategorizedCount)
        assertFalse(b.data.isOnBudget("home"))
    }

    @Test
    fun loansAreSavedAsTrackingAccounts() {
        val b = budget()
        b.saveAccount(Account("m", "Mortgage", AccountType.MORTGAGE, onBudget = true), -25_000_000, LocalDate(2026, 9, 30))
        assertFalse(b.data.accounts.getValue("m").onBudget)
        assertEquals(1_000_000L, BudgetMath.compute(b.data, oct).readyToAssign)
    }

    @Test
    fun mortgageTypeSplitsPayments() {
        val b = budget()
        b.saveAccount(Account("m", "Mortgage", AccountType.MORTGAGE, loan = LoanTerms(6.0, 40000)), -30_000_000, LocalDate(2026, 9, 30))
        b.saveTransaction(
            Transaction("p", "chk", LocalDate(2026, 10, 1), -250000, "Main Street Mortgage", categoryId = "seed-rent-mortgage",
                loanSplit = LoanSplit("m", 0, 0, 0))
        )
        assertEquals(60000L, b.data.transactions.getValue("p").loanSplit!!.principal)
        // The budget sees the $2,500 payment, not the loan balance.
        val m = BudgetMath.compute(b.data, oct)
        assertEquals(-250000L, m.category("seed-rent-mortgage").activity)
        assertEquals(1_000_000L, m.readyToAssign)
    }

    @Test
    fun correctingPrincipalAdjustsBalanceAndResplits() {
        val b = budget()
        b.saveAccount(Account("m", "Mortgage", AccountType.MORTGAGE, loan = LoanTerms(6.0, 40000)), -30_000_000, LocalDate(2026, 9, 30))
        b.saveTransaction(
            Transaction("p", "chk", LocalDate(2026, 10, 1), -250000, "Mortgage", categoryId = "seed-rent-mortgage",
                loanSplit = LoanSplit("m", 0, 0, 0))
        )
        // Statement says $250,000 is owed today (after the October payment).
        b.setLoanPrincipal("m", 25_000_000)
        assertEquals(-25_000_000L, b.data.balance("m"))
        // Before the payment X was owed, with X - (2,100 - X x 0.5%) = 250,000, so X = 250,845.77:
        // interest 1,254.23 and principal 845.77.
        val split = b.data.transactions.getValue("p").loanSplit!!
        assertEquals(125423L, split.interest)
        assertEquals(84577L, split.principal)
        assertEquals(-25_000_000L, b.data.balance("m"), "still matches after re-splitting")
    }
}
