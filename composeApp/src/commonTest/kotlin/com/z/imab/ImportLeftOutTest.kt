package com.z.imab

import com.z.imab.data.Budget
import com.z.imab.importer.ImportAction
import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.ImportWindow
import com.z.imab.importer.MatchKind
import com.z.imab.importer.OfxTransaction
import com.z.imab.model.Account
import com.z.imab.model.AccountType
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A transaction left out of one import still shows up in the next. */
class ImportLeftOutTest {
    private fun tx(fit: String, day: Int, cents: Long, name: String) =
        OfxTransaction(fit, "DEBIT", LocalDate(2026, 11, day), cents, name, "", null)

    private val first = listOf(tx("A", 1, -1000, "COFFEE"), tx("B", 2, -36674, "WAREHOUSE CLUB"), tx("C", 3, -2000, "GAS"))
    private val second = first + tx("D", 4, 5169, "REFUND") + tx("E", 6, -500, "PARKING")

    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("card", "Card", AccountType.CREDIT_CARD), 0, LocalDate(2026, 10, 31))
    }

    @Test
    fun aRowLeftBehindIsOfferedAgain() {
        val b = budget()
        // First import: everything except the warehouse club purchase, which was left to split later.
        val rows = ImportMatcher.analyze(first, "card", b.data).map { if (it.importId == "B") it.copy(action = ImportAction.SKIP) else it }
        b.applyImport("card", null, rows)
        b.applyImport("card", null, ImportMatcher.analyze(listOf(second[3]), "card", b.data))

        val from = ImportWindow.defaultStart(b.data, "card", second)
        assertEquals(LocalDate(2026, 11, 4), from)
        val again = ImportWindow.rows(b.data, "card", second, from)
        val left = again.single { it.importId == "B" }
        assertEquals(MatchKind.NEW, left.kind)
        assertEquals(ImportAction.ADD, left.action)
        assertTrue(left.reason.startsWith("Left out of an earlier import"))
        // What was imported stays hidden; only the new one and the one left out are to add.
        assertEquals(setOf("B", "E"), again.filter { it.action == ImportAction.ADD }.map { it.importId }.toSet())
        assertTrue(again.none { it.importId in setOf("A", "C") })
    }

    @Test
    fun nothingBeforeTheFirstImportIsDugUp() {
        val b = budget()
        // The file goes back further than the account; the first import started at 11/02.
        b.applyImport("card", null, ImportMatcher.analyze(first.drop(1), "card", b.data))
        val rows = ImportWindow.rows(b.data, "card", second, ImportWindow.defaultStart(b.data, "card", second))
        assertTrue(rows.none { it.importId == "A" })
        assertEquals(listOf("D", "E"), rows.filter { it.action == ImportAction.ADD }.map { it.importId })
    }
}
