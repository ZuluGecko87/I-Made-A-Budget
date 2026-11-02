package com.z.imab

import com.z.imab.data.Budget
import com.z.imab.importer.ImportAction
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.MatchKind
import com.z.imab.importer.OfxTransaction
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import com.z.imab.model.Transaction
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Merging duplicates you picked yourself. */
class MergeDuplicatesTest {
    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 9, 30))
        it.saveAccount(Account("sav", "Savings", AccountType.SAVINGS))
    }

    private fun bankTx(fit: String, day: Int, cents: Long, name: String) =
        OfxTransaction(fit, "DEBIT", LocalDate(2026, 10, day), cents, name, "", null)

    private fun Budget.importAsNew(vararg txns: OfxTransaction) =
        applyImport("chk", null, ImportMatcher.analyze(txns.toList(), "chk", data).map { it.copy(action = ImportAction.ADD) })

    private fun Budget.inChecking() = data.transactionsByAccount["chk"].orEmpty().filter { it.payee != "Starting Balance" }

    @Test
    fun theSameThingEnteredTwiceBecomesOne() {
        val b = budget()
        b.saveTransaction(Transaction("a", "chk", LocalDate(2026, 10, 3), -2500, "Bakery", categoryId = "seed-dining-out"))
        b.saveTransaction(Transaction("b", "chk", LocalDate(2026, 10, 3), -2500, "", "birthday cake", cleared = true))
        b.mergeDuplicates("a", listOf("a", "b"))
        val t = b.inChecking().single()
        assertEquals("a", t.id)
        assertEquals("Bakery", t.payee)
        assertEquals("birthday cake", t.memo, "a blank memo is filled from the duplicate")
        assertEquals("seed-dining-out", t.categoryId)
        assertTrue(t.cleared)
        assertTrue(t.merged)
        assertEquals(97500, b.data.balance("chk"))
    }

    @Test
    fun keepingTheUncategorizedOneTakesTheOtherCategory() {
        val b = budget()
        b.saveTransaction(Transaction("a", "chk", LocalDate(2026, 10, 3), -2500, "Bakery", categoryId = "seed-dining-out"))
        b.saveTransaction(Transaction("b", "chk", LocalDate(2026, 10, 4), -2500, "BAKERY 12"))
        b.mergeDuplicates("b", listOf("a", "b"))
        val t = b.inChecking().single()
        assertEquals("BAKERY 12", t.payee)
        assertEquals("seed-dining-out", t.categoryId)
        assertFalse(b.data.needsCategory(t))
    }

    @Test
    fun twoBankCopiesStayMergedWhenTheFileIsImportedAgain() {
        val b = budget()
        // The bank listed the same purchase twice under different ids, both imported.
        b.importAsNew(bankTx("F1", 5, -1999, "HARDWARE"), bankTx("F2", 6, -1999, "HARDWARE STORE"))
        b.saveTransaction(Transaction("mine", "chk", LocalDate(2026, 10, 4), -1999, "Hardware", categoryId = "seed-household"))
        val ids = b.inChecking().map { it.id }
        b.mergeDuplicates("mine", ids)

        val t = b.inChecking().single()
        assertEquals("mine", t.id)
        assertEquals(LocalDate(2026, 10, 5), t.date, "takes the bank's date")
        assertEquals("F1", t.importId)
        assertEquals(listOf("F2"), t.otherImportIds)

        val again = ImportMatcher.analyze(listOf(bankTx("F1", 5, -1999, "HARDWARE"), bankTx("F2", 6, -1999, "HARDWARE STORE")), "chk", b.data)
        assertTrue(again.all { it.kind == MatchKind.DUPLICATE && it.action == ImportAction.SKIP })
    }

    @Test
    fun aDuplicatedTransferLosesItsOtherLegToo() {
        val b = budget()
        b.saveTransaction(Transaction("x1", "chk", LocalDate(2026, 10, 7), -10000, transferAccountId = "sav"))
        b.saveTransaction(Transaction("x2", "chk", LocalDate(2026, 10, 7), -10000, transferAccountId = "sav"))
        assertEquals(20000, b.data.balance("sav"))
        b.mergeDuplicates("x1", listOf("x1", "x2"))
        assertEquals(1, b.inChecking().size)
        assertEquals(10000, b.data.balance("sav"), "the duplicate's savings leg is gone too")
        assertEquals("x1", b.data.transactionsByAccount["sav"]!!.single().transferPairId)
    }

    @Test
    fun keepingAPlainCopyTakesOverTheTransfer() {
        val b = budget()
        b.saveTransaction(Transaction("x1", "chk", LocalDate(2026, 10, 7), -10000, transferAccountId = "sav"))
        b.saveTransaction(Transaction("plain", "chk", LocalDate(2026, 10, 7), -10000, "Savings"))
        b.mergeDuplicates("plain", listOf("x1", "plain"))
        val t = b.inChecking().single()
        assertEquals("sav", t.transferAccountId)
        assertEquals(10000, b.data.balance("sav"))
        assertEquals("plain", b.data.transactionsByAccount["sav"]!!.single().transferPairId)
    }

    @Test
    fun onlyTransactionsInTheSameAccountAreMerged() {
        val b = budget()
        b.saveTransaction(Transaction("a", "chk", LocalDate(2026, 10, 3), -2500, "Bakery"))
        b.saveTransaction(Transaction("s", "sav", LocalDate(2026, 10, 3), -2500, "Bakery"))
        b.mergeDuplicates("a", listOf("a", "s"))
        assertEquals(1, b.data.transactionsByAccount["sav"]!!.size)
        assertNull(b.inChecking().single().importId)
        assertFalse(b.inChecking().single().merged)
    }
}
