package com.z.imab

import com.z.imab.data.Budget
import com.z.imab.model.Account
import com.z.imab.model.Frequency
import com.z.imab.model.ScheduledTransaction
import com.z.imab.model.Transaction
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayeeTest {
    private fun budget() = Budget("dev").also {
        it.seedDefaults()
        it.saveAccount(Account("chk", "Checking"), 100000, LocalDate(2026, 9, 30))
        it.saveTransaction(Transaction("1", "chk", LocalDate(2026, 10, 1), -500, "Kroger"))
        it.saveTransaction(Transaction("2", "chk", LocalDate(2026, 10, 2), -700, "Kroger"))
        it.saveTransaction(Transaction("3", "chk", LocalDate(2026, 10, 3), -900, "Krogre"))
        it.saveTransaction(Transaction("4", "chk", LocalDate(2026, 10, 4), -1500, "Target"))
        it.saveScheduled(ScheduledTransaction("s", "chk", LocalDate(2026, 11, 1), LocalDate(2026, 11, 1), Frequency.MONTHLY, -100000, "Landlord"))
    }

    @Test
    fun payeesAreListedMostUsedFirstWithoutAppEntries() {
        val b = budget()
        assertEquals(listOf("Kroger" to 2, "Krogre" to 1, "Landlord" to 1, "Target" to 1), b.data.payees)
        assertTrue(b.data.payees.none { it.first == "Starting Balance" })
    }

    @Test
    fun searchMatchesAnyPartIgnoringCase() {
        val b = budget()
        assertEquals(listOf("Kroger", "Krogre"), b.data.searchPayees("kro"))
        assertEquals(listOf("Target"), b.data.searchPayees("GET"))
        assertEquals(4, b.data.searchPayees("").size)
    }

    @Test
    fun renamingFixesEveryTransactionAndMergesDuplicates() {
        val b = budget()
        b.renamePayee("Krogre", "Kroger")
        assertEquals("Kroger", b.data.transactions.getValue("3").payee)
        assertEquals(listOf("Kroger" to 3, "Landlord" to 1, "Target" to 1), b.data.payees)

        b.renamePayee("Landlord", "Oak Street Apartments")
        assertEquals("Oak Street Apartments", b.data.scheduled.getValue("s").payee)
    }
}
