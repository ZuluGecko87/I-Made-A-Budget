package com.z.imab.importer

import com.z.imab.budget.Scheduler
import com.z.imab.model.BudgetData
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.abs

enum class MatchKind { NEW, DUPLICATE, MATCH_EXISTING, MATCH_SCHEDULED }

enum class ImportAction { SKIP, ADD, MATCH }

data class ImportRow(
    val source: OfxTransaction,
    val importId: String,
    val kind: MatchKind,
    val reason: String,
    val action: ImportAction,
    /** Existing transaction this row matched (MATCH_EXISTING, or the earlier copy for DUPLICATE). */
    val matchedTxnId: String? = null,
    val scheduledId: String? = null,
    val scheduledDate: LocalDate? = null,
    /** For ADD: the category or transfer account to use. */
    val categoryId: String? = null,
    val transferAccountId: String? = null,
    /** For ADD: treat as a payment on this loan (split into principal, interest and escrow). */
    val loanAccountId: String? = null,
    /** For ADD: save the choice above as a rule for payees containing [ruleMatch]. */
    val makeRule: Boolean = false,
    val ruleMatch: String = "",
) {
    val payee: String get() = source.name.ifBlank { source.memo }
}

object ImportMatcher {
    /** How far apart the bank's date and a manually entered / scheduled date may be. */
    const val DAY_WINDOW = 4

    /** Gives every bank transaction a stable id, synthesizing one when the bank omits FITID. */
    fun withImportIds(txns: List<OfxTransaction>): List<Pair<OfxTransaction, String>> {
        val seen = HashMap<String, Int>()
        return txns.map { t ->
            val fit = t.fitId?.trim()?.takeIf { it.isNotEmpty() }
            if (fit != null) {
                t to fit
            } else {
                val base = "gen:${t.date}:${t.amount}:${normalizePayee(t.name)}"
                val n = seen.getOrElse(base) { 0 }
                seen[base] = n + 1
                t to "$base:$n"
            }
        }
    }

    fun analyze(
        txns: List<OfxTransaction>,
        accountId: String,
        data: BudgetData,
        window: Int = DAY_WINDOW,
    ): List<ImportRow> {
        val existing = data.transactionsByAccount[accountId].orEmpty()
        val byImportId = existing.filter { it.importId != null }.associateBy { it.importId!! }
        val claimed = HashSet<String>()
        val claimedSchedule = HashSet<String>()
        val seenInFile = HashSet<String>()

        return withImportIds(txns).map { (src, importId) ->
            if (!seenInFile.add(importId)) {
                return@map ImportRow(src, importId, MatchKind.DUPLICATE, "Listed twice in this file", ImportAction.SKIP)
            }

            byImportId[importId]?.let { dup ->
                claimed += dup.id
                return@map ImportRow(
                    src, importId, MatchKind.DUPLICATE,
                    "Already in budget (${data.payeeLabel(dup)}, ${display(dup.date)})", ImportAction.SKIP,
                    matchedTxnId = dup.id,
                )
            }

            // Some banks re-issue FITIDs between downloads; same date + amount + payee is still a duplicate.
            val payeeKey = normalizePayee(src.name)
            existing.firstOrNull {
                it.importId != null && it.id !in claimed && it.date == src.date &&
                    it.amount == src.amount && normalizePayee(it.payee) == payeeKey
            }?.let { dup ->
                claimed += dup.id
                return@map ImportRow(
                    src, importId, MatchKind.DUPLICATE,
                    "Same date, amount and payee already imported", ImportAction.SKIP,
                    matchedTxnId = dup.id,
                )
            }

            // Something entered by hand, from a schedule, or as the other leg of a transfer.
            existing
                .filter {
                    it.importId == null && it.id !in claimed && it.amount == src.amount &&
                        it.payee != ImportWindow.STARTING_BALANCE_PAYEE && daysApart(it.date, src.date) <= window
                }
                .minByOrNull { daysApart(it.date, src.date) }
                ?.let { match ->
                    claimed += match.id
                    return@map ImportRow(
                        src, importId, MatchKind.MATCH_EXISTING,
                        "You entered ${data.payeeLabel(match)} for the same amount on ${display(match.date)}" +
                            daysApart(match.date, src.date).let { if (it == 0) " (same day)" else " ($it day${if (it == 1) "" else "s"} apart)" },
                        ImportAction.MATCH,
                        matchedTxnId = match.id,
                    )
                }

            // An upcoming scheduled transaction the bank has already posted.
            findSchedule(src, accountId, data, window, claimedSchedule)?.let { (sid, date) ->
                claimedSchedule += "$sid|$date"
                val s = data.scheduled.getValue(sid)
                return@map ImportRow(
                    src, importId, MatchKind.MATCH_SCHEDULED,
                    "Matches scheduled ${s.payee.ifBlank { if (s.transferAccountId != null) "transfer" else "transaction" }} due ${display(date)}",
                    ImportAction.MATCH,
                    scheduledId = sid, scheduledDate = date,
                )
            }

            // Imports stay uncategorized unless one of your rules says otherwise.
            val name = src.name.ifBlank { src.memo }
            val rule = RuleBook.find(data, name)
            ImportRow(
                src, importId, MatchKind.NEW,
                if (rule != null) "New · rule \"${rule.match}\" → ${RuleBook.describe(data, rule)}" else "New",
                ImportAction.ADD,
                categoryId = rule?.categoryId,
                transferAccountId = rule?.transferAccountId?.takeIf { it != accountId },
                loanAccountId = rule?.loanAccountId?.takeIf { it != accountId && data.accounts[it]?.loan != null },
                ruleMatch = RuleBook.suggestedMatch(name),
            )
        }
    }

    private fun findSchedule(
        src: OfxTransaction,
        accountId: String,
        data: BudgetData,
        window: Int,
        claimed: Set<String>,
    ): Pair<String, LocalDate>? {
        val from = src.date.minus(window, DateTimeUnit.DAY)
        val to = src.date.plus(window, DateTimeUnit.DAY)
        return data.scheduled.values
            .filter {
                (it.accountId == accountId && it.amount == src.amount) ||
                    (it.transferAccountId == accountId && -it.amount == src.amount)
            }
            .flatMap { s -> Scheduler.occurrencesBetween(s, from, to).map { s.id to it } }
            .filter { (id, d) -> "$id|$d" !in claimed && data.transactions[Scheduler.occurrenceTxnId(id, d)] == null }
            .minByOrNull { daysApart(it.second, src.date) }
    }

    /** MM/DD/YY, matching how dates are shown elsewhere in the app. */
    private fun display(d: LocalDate) =
        "${d.monthNumber.toString().padStart(2, '0')}/${d.dayOfMonth.toString().padStart(2, '0')}/${(d.year % 100).toString().padStart(2, '0')}"

    fun daysApart(a: LocalDate, b: LocalDate): Int = abs(a.toEpochDays() - b.toEpochDays()).toInt()

    /** "AMAZON MKTPL*AB12C3 AMZN.COM/BILL WA" -> "amazon mktpl amzn com bill wa". */
    fun normalizePayee(s: String): String =
        s.lowercase().map { if (it.isLetter()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
