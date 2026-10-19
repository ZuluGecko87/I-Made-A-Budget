package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.budget.LoanMath
import com.z.imab.data.Budget
import com.z.imab.data.principalLegId
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.MatchKind
import com.z.imab.importer.OfxTransaction
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.LoanTerms
import com.z.imab.model.PayeeRule
import com.z.imab.model.READY_TO_ASSIGN
import com.z.imab.model.Transaction
import com.z.imab.model.YearMonth
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesAndLoanTest {
    private val mortgageCat = "seed-rent-mortgage"
    // $300,000 at 6% with $400/month escrow.
    private val terms = LoanTerms(annualRatePct = 6.0, monthlyEscrow = 40000)

    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 1_000_000, LocalDate(2026, 8, 31))
        it.saveAccount(Account("home", "Home Loan", AccountType.LOAN, onBudget = false, loan = terms), -30_000_000, LocalDate(2026, 8, 31))
    }

    private fun bank(fit: String, month: Int, day: Int, cents: Long, name: String) =
        OfxTransaction(fit, "DEBIT", LocalDate(2026, month, day), cents, name, "", null)

    @Test
    fun importsAreUncategorizedWithoutARule() {
        val b = budget()
        // Even with history for this payee, nothing is guessed.
        b.saveTransaction(Transaction("old", "chk", LocalDate(2026, 9, 1), -4512, "KROGER #123", categoryId = "seed-groceries"))
        val row = ImportMatcher.analyze(listOf(bank("K", 10, 1, -3000, "KROGER #123")), "chk", b.data).single()
        assertEquals(MatchKind.NEW, row.kind)
        assertNull(row.categoryId)
        assertEquals("kroger", row.ruleMatch)
    }

    @Test
    fun ruleCategorizesMatchingPayees() {
        val b = budget()
        b.saveRule(PayeeRule("r", "kroger", categoryId = "seed-groceries"))
        val rows = ImportMatcher.analyze(
            listOf(bank("K1", 10, 1, -3000, "KROGER #123 CINCINNATI"), bank("K2", 10, 2, -999, "KROGERS FUEL")),
            "chk", b.data,
        )
        assertEquals("seed-groceries", rows[0].categoryId, "matches whole words")
        assertNull(rows[1].categoryId, "\"krogers\" isn't the word \"kroger\"")
    }

    @Test
    fun longestRuleWins() {
        val b = budget()
        b.saveRule(PayeeRule("a", "amazon", categoryId = "seed-shopping"))
        b.saveRule(PayeeRule("b", "amazon prime", categoryId = "seed-entertainment"))
        val row = ImportMatcher.analyze(listOf(bank("A", 10, 1, -1499, "AMAZON PRIME*2K4")), "chk", b.data).single()
        assertEquals("seed-entertainment", row.categoryId)
    }

    @Test
    fun ticking_always_creates_a_rule_used_next_month() {
        val b = budget()
        val oct = ImportMatcher.analyze(listOf(bank("M10", 10, 1, -250000, "MAIN STREET MORTGAGE 0042")), "chk", b.data)
            .map { it.copy(categoryId = mortgageCat, loanAccountId = "home", makeRule = true) }
        b.applyImport("chk", null, oct)
        val rule = b.data.rules.values.single()
        assertEquals("main street mortgage", rule.match)
        assertEquals("home", rule.loanAccountId)

        val nov = ImportMatcher.analyze(listOf(bank("M11", 11, 1, -250000, "MAIN STREET MORTGAGE 0042")), "chk", b.data).single()
        assertEquals(mortgageCat, nov.categoryId)
        assertEquals("home", nov.loanAccountId)
    }

    @Test
    fun mortgagePaymentSplitsIntoPrincipalInterestEscrow() {
        val b = budget()
        b.applyImport(
            "chk", null,
            ImportMatcher.analyze(listOf(bank("M10", 10, 1, -250000, "MAIN STREET MORTGAGE")), "chk", b.data)
                .map { it.copy(categoryId = mortgageCat, loanAccountId = "home") },
        )
        val pay = b.data.transactions.values.single { it.importId == "M10" }
        val split = pay.loanSplit!!
        // Interest: 300,000 x 6% / 12 = 1,500. Escrow 400. Principal = 2,500 - 1,500 - 400 = 600.
        assertEquals(150000L, split.interest)
        assertEquals(40000L, split.escrow)
        assertEquals(60000L, split.principal)
        assertEquals(mortgageCat, pay.categoryId)

        val leg = b.data.transactions.getValue(principalLegId(pay.id))
        assertEquals("home", leg.accountId)
        assertEquals(60000L, leg.amount)
        assertEquals(-29_940_000L, b.data.balance("home"), "principal left: 299,400")

        // The whole payment is budgeted from the mortgage category; the loan entry isn't.
        val m = BudgetMath.compute(b.data, YearMonth(2026, 10))
        assertEquals(-250000L, m.category(mortgageCat).activity)
        assertEquals(0, b.data.uncategorizedCount)
    }

    @Test
    fun laterPaymentsUseTheReducedBalanceAndRecalculate() {
        val b = budget()
        fun pay(id: String, month: Int) = b.saveTransaction(
            Transaction(id, "chk", LocalDate(2026, month, 1), -250000, "Main Street Mortgage", categoryId = mortgageCat,
                loanSplit = com.z.imab.model.LoanSplit("home", 0, 0, 0))
        )
        pay("nov", 11)
        pay("oct", 10) // entered out of order
        // October: interest on 300,000 = 1,500.00 -> principal 600.00.
        assertEquals(60000L, b.data.transactions.getValue("oct").loanSplit!!.principal)
        // November: interest on 299,400 = 1,497.00 -> principal 603.00.
        val nov = b.data.transactions.getValue("nov").loanSplit!!
        assertEquals(149700L, nov.interest)
        assertEquals(60300L, nov.principal)

        // Deleting October puts November back on the full balance.
        b.deleteTransaction("oct")
        assertEquals(60000L, b.data.transactions.getValue("nov").loanSplit!!.principal)
        assertTrue(principalLegId("oct") !in b.data.transactions)
    }

    @Test
    fun unlinkingRemovesThePrincipalEntry() {
        val b = budget()
        b.saveTransaction(Transaction("p", "chk", LocalDate(2026, 10, 1), -250000, "Mortgage", categoryId = mortgageCat))
        b.setLoanPayment("p", "home")
        assertEquals(60000L, b.data.transactions.getValue(principalLegId("p")).amount)
        b.setLoanPayment("p", null)
        assertNull(b.data.transactions.getValue("p").loanSplit)
        assertTrue(principalLegId("p") !in b.data.transactions)
        assertEquals(-30_000_000L, b.data.balance("home"))
    }

    @Test
    fun payoffEstimate() {
        // 300,000 at 6% with a 1,798.65 principal-and-interest payment is a 30-year loan.
        val p = LoanMath.payoff(30_000_000, 6.0, 179865)!!
        // The rounded-down payment leaves a few dollars for a 361st month (lenders fold it into the last payment).
        assertTrue(p.months in 360..361, "got ${p.months} months")
        assertTrue(p.totalInterest in 34_750_000L..34_760_000L, "about 347,515 in interest, got ${p.totalInterest}")
        assertNull(LoanMath.payoff(30_000_000, 6.0, 150000), "doesn't cover the interest")
    }

    @Test
    fun startingBalanceInRtaUnaffected() {
        val b = budget()
        assertEquals(1_000_000L, BudgetMath.compute(b.data, YearMonth(2026, 10)).readyToAssign)
        assertEquals(READY_TO_ASSIGN, b.data.transactions.values.first { it.accountId == "chk" }.categoryId)
    }
}
