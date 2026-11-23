package com.z.imab.receipt

import com.z.imab.importer.ImportMatcher
import com.z.imab.importer.RuleBook
import com.z.imab.model.Account
import com.z.imab.model.BudgetData
import com.z.imab.model.Transaction
import kotlinx.datetime.LocalDate

/** How a scanned receipt became a draft transaction, to explain it in the form. */
data class ReceiptDraft(
    val transaction: Transaction,
    val scan: ReceiptScan,
    /** The account the card on the receipt pays from, if one could be told. */
    val cardAccount: Account?,
)

/** Turns what was read from a receipt into a transaction, using what the budget already knows. */
object ReceiptMatcher {
    private val brandWords = mapOf(
        "Visa" to listOf("visa"),
        "Mastercard" to listOf("mastercard", "master card", "mc"),
        "Amex" to listOf("amex", "american express"),
        "Discover" to listOf("discover"),
    )

    /**
     * The account the receipt's card pays from: one that already has those last 4 digits (from an
     * earlier receipt, its bank file or its name), else the only open card named after the brand.
     */
    fun cardAccount(data: BudgetData, last4: String?, brand: String?): Account? {
        val open = data.sortedAccounts.filter { !it.closed }
        if (last4 != null) {
            open.firstOrNull { last4 in it.cardLast4 }?.let { return it }
            open.firstOrNull { a -> a.importKeys.any { it.endsWith(last4) } }?.let { return it }
            open.firstOrNull { Regex("""(?<!\d)$last4(?!\d)""").containsMatchIn(it.name) }?.let { return it }
        }
        val words = brand?.let { brandWords[it] } ?: return null
        val named = open.filter { a -> a.type.isCredit && words.any { w -> Regex("""\b$w\b""").containsMatchIn(a.name.lowercase()) } }
        return named.singleOrNull()
    }

    /** A payee already in the budget for this merchant ("Trader Joe's" for "TRADER JOE'S #552"), else the merchant. */
    fun payee(data: BudgetData, merchant: String): String {
        val key = ImportMatcher.normalizePayee(merchant)
        if (key.isEmpty()) return merchant
        val padded = " $key "
        return data.payees.firstOrNull { (p, _) ->
            val k = ImportMatcher.normalizePayee(p)
            k == key || (k.length >= 4 && padded.contains(" $k ")) || (key.length >= 4 && " $k ".contains(padded))
        }?.first ?: merchant
    }

    /** The category for [payee]: an auto-categorize rule's, else the one it was given last time. */
    fun category(data: BudgetData, payee: String): String? {
        RuleBook.find(data, payee)?.let { rule -> if (rule.transferAccountId == null) return rule.categoryId }
        val p = payee.trim().lowercase()
        return data.transactions.values
            .filter { it.payee.trim().lowercase() == p && it.categoryId != null && !it.isSplit && it.transferAccountId == null }
            .maxByOrNull { it.date }?.categoryId
    }

    /**
     * A new, unsaved outflow from the receipt. The account is the card's when it's known, else
     * [fallbackAccountId]; missing details are left for the person to fill in.
     */
    fun draft(data: BudgetData, scan: ReceiptScan, fallbackAccountId: String, id: String, today: LocalDate): ReceiptDraft {
        val card = cardAccount(data, scan.cardLast4, scan.cardBrand)
        val payee = scan.merchant?.let { payee(data, it) }.orEmpty()
        val t = Transaction(
            id = id,
            accountId = card?.id ?: fallbackAccountId,
            date = scan.date ?: today,
            amount = -(scan.total ?: 0),
            payee = payee,
            categoryId = payee.takeIf { it.isNotEmpty() }?.let { category(data, it) },
        )
        return ReceiptDraft(t, scan, card)
    }
}
