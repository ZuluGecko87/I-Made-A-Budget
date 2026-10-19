package com.z.imab.importer

import com.z.imab.budget.Scheduler
import com.z.imab.model.BudgetData
import com.z.imab.model.READY_TO_ASSIGN
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
        val history = PayeeHistory(data)

        return withImportIds(txns).map { (src, importId) ->
            if (!seenInFile.add(importId)) {
                return@map ImportRow(src, importId, MatchKind.DUPLICATE, "Listed twice in this file", ImportAction.SKIP)
            }

            byImportId[importId]?.let { dup ->
                claimed += dup.id
                return@map ImportRow(
                    src, importId, MatchKind.DUPLICATE,
                    "Already in budget (${data.payeeLabel(dup)}, ${dup.date})", ImportAction.SKIP,
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
                        "Matches ${data.payeeLabel(match)} on ${match.date}", ImportAction.MATCH,
                        matchedTxnId = match.id,
                    )
                }

            // An upcoming scheduled transaction the bank has already posted.
            findSchedule(src, accountId, data, window, claimedSchedule)?.let { (sid, date) ->
                claimedSchedule += "$sid|$date"
                val s = data.scheduled.getValue(sid)
                return@map ImportRow(
                    src, importId, MatchKind.MATCH_SCHEDULED,
                    "Matches scheduled ${s.payee.ifBlank { if (s.transferAccountId != null) "transfer" else "transaction" }} due $date",
                    ImportAction.MATCH,
                    scheduledId = sid, scheduledDate = date,
                )
            }

            val guess = history.guess(src.name)
            ImportRow(
                src, importId, MatchKind.NEW, if (guess != null) "New - category from past payee" else "New",
                ImportAction.ADD,
                categoryId = guess?.categoryId,
                transferAccountId = guess?.transferAccountId?.takeIf { it != accountId },
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

    fun daysApart(a: LocalDate, b: LocalDate): Int = abs(a.toEpochDays() - b.toEpochDays())

    /** "AMAZON MKTPL*AB12C3 AMZN.COM/BILL WA" -> "amazon mktpl amzn com bill wa". */
    fun normalizePayee(s: String): String =
        s.lowercase().map { if (it.isLetter()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    /** Remembers the category last used for each payee so new imports can be pre-filled. */
    class PayeeHistory(data: BudgetData) {
        data class Guess(val categoryId: String?, val transferAccountId: String?)

        private val exact = HashMap<String, Guess>()
        private val firstWords = HashMap<String, Guess>()

        init {
            data.transactions.values
                .filter { it.categoryId != null || it.transferAccountId != null }
                .sortedBy { it.date }
                .forEach { t ->
                    val key = normalizePayee(t.payee)
                    if (key.isEmpty()) return@forEach
                    val g = Guess(t.categoryId?.takeIf { it in data.categories || it == READY_TO_ASSIGN }, t.transferAccountId)
                    if (g.categoryId == null && g.transferAccountId == null) return@forEach
                    exact[key] = g
                    firstWords[prefix(key)] = g
                }
        }

        private fun prefix(key: String) = key.split(' ').take(2).joinToString(" ")

        fun guess(payee: String): Guess? {
            val key = normalizePayee(payee)
            if (key.isEmpty()) return null
            return exact[key] ?: firstWords[prefix(key)]?.takeIf { prefix(key).length >= 4 }
        }
    }
}
