package com.z.imab

import com.z.imab.budget.BudgetMath
import com.z.imab.data.Budget
import com.z.imab.importer.ImportAction
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.OfxTransaction
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.SplitLine
import com.z.imab.model.YearMonth
import com.z.imab.model.paymentCategoryId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Splitting a row from a bank file before it's imported. */
class ImportSplitTest {
    private val oct = YearMonth(2026, 10)

    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 9, 30))
        it.saveAccount(Account("card", "Card", AccountType.CREDIT_CARD))
        it.setAssigned(oct, "seed-groceries", 20000)
        it.setAssigned(oct, "seed-household", 5000)
    }

    private val purchase = OfxTransaction("F1", "DEBIT", LocalDate(2026, 10, 5), -10000, "BIG BOX 12", "", null)

    @Test
    fun aSplitRowIsImportedWithItsLines() {
        val b = budget()
        val lines = listOf(SplitLine("seed-groceries", -7000), SplitLine("seed-household", -3000))
        val row = ImportMatcher.analyze(listOf(purchase), "card", b.data).single()
            .copy(action = ImportAction.ADD, splits = lines, makeRule = true, ruleMatch = "BIG BOX")
        b.applyImport("card", null, listOf(row))

        val t = b.data.transactionsByAccount.getValue("card").single()
        assertEquals(lines, t.splits)
        assertNull(t.categoryId)
        assertEquals("F1", t.importId)
        assertTrue(t.cleared)
        assertTrue(b.data.rules.isEmpty(), "a split doesn't become a one-category rule")

        val mb = BudgetMath.compute(b.data, oct)
        assertEquals(13000, mb.category("seed-groceries").available)
        assertEquals(2000, mb.category("seed-household").available)
        assertEquals(10000, mb.category(paymentCategoryId("card")).available)
    }
}
