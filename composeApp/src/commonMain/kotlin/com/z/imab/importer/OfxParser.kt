package com.z.imab.importer

import com.z.imab.model.Money
import kotlinx.datetime.LocalDate

data class OfxTransaction(
    val fitId: String?,
    val type: String?,
    val date: LocalDate,
    val amount: Long,
    val name: String,
    val memo: String,
    val checkNumber: String?,
)

data class OfxStatement(
    /**
     * Identifies the bank account so the next file from it goes to the same IMAB account. Only the
     * bank's id and the last 4 digits ([AccountKeys]); the full account or card number is never stored.
     */
    val accountKey: String,
    val bankId: String?,
    val accountId: String,
    val accountType: String?,
    val isCreditCard: Boolean,
    val currency: String?,
    val ledgerBalance: Long?,
    val balanceDate: LocalDate?,
    val transactions: List<OfxTransaction>,
) {
    /** Masked number for display, e.g. "••1234". */
    val shortName: String get() = "••" + accountId.takeLast(4)
}

/**
 * Account keys hold no full account or card numbers: "bank-<routing/bank id>-••1234" for a bank
 * account, "card-••1234" for a card. Keys saved by older versions ("<bank id>-<full number>" or the
 * full card number) are converted with [mask].
 */
object AccountKeys {
    fun of(bankId: String?, accountId: String): String {
        val last4 = accountId.filter { it.isLetterOrDigit() }.takeLast(4)
        return if (bankId.isNullOrBlank()) "card-••$last4" else "bank-${bankId.trim()}-••$last4"
    }

    fun isMasked(key: String) = key.startsWith("bank-") || key.startsWith("card-")

    /** The masked form of a key saved by an older version. */
    fun mask(key: String): String {
        if (isMasked(key)) return key
        val dash = key.indexOf('-')
        return if (dash > 0) of(key.substring(0, dash), key.substring(dash + 1)) else of(null, key)
    }
}

/**
 * Reads QuickBooks Web Connect (.qbo), Quicken (.qfx) and Money (.ofx) downloads. All three are
 * OFX: either the 1.x SGML flavor (leaf tags aren't closed) or the 2.x XML flavor.
 */
object OfxParser {
    private val tagRegex = Regex("<(/?)([A-Za-z0-9._]+)>([^<]*)")

    fun parse(text: String): List<OfxStatement> {
        val start = text.indexOf("<OFX>", ignoreCase = true)
        require(start >= 0) { "This doesn't look like a QBO/OFX/QFX bank file." }

        val statements = mutableListOf<OfxStatement>()
        var stmt: StatementBuilder? = null
        var txn: MutableMap<String, String>? = null
        var inAccountFrom = false
        var inAccountTo = false
        var inLedgerBalance = false

        fun flushTxn() {
            val t = txn ?: return
            txn = null
            buildTxn(t)?.let { stmt?.transactions?.add(it) }
        }

        fun flushStmt() {
            flushTxn()
            stmt?.build()?.let { statements += it }
            stmt = null
        }

        for (m in tagRegex.findAll(text, start)) {
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].uppercase()
            val value = decodeEntities(m.groupValues[3].trim())
            if (!closing) {
                when (tag) {
                    "STMTRS", "CCSTMTRS" -> { flushStmt(); stmt = StatementBuilder(tag == "CCSTMTRS") }
                    "STMTTRN" -> { flushTxn(); txn = mutableMapOf() }
                    "BANKACCTFROM", "CCACCTFROM" -> inAccountFrom = true
                    "BANKACCTTO", "CCACCTTO" -> inAccountTo = true
                    "LEDGERBAL" -> inLedgerBalance = true
                    else -> if (value.isNotEmpty()) {
                        val t = txn
                        val s = stmt
                        when {
                            t != null -> if (!inAccountTo) t.getOrPut(tag) { value }
                            s != null && inAccountFrom -> s.account[tag] = value
                            s != null && inLedgerBalance -> s.balance[tag] = value
                            s != null && tag == "CURDEF" -> s.currency = value
                        }
                    }
                }
            } else {
                when (tag) {
                    "STMTTRN", "BANKTRANLIST" -> flushTxn()
                    "STMTRS", "CCSTMTRS" -> flushStmt()
                    "BANKACCTFROM", "CCACCTFROM" -> inAccountFrom = false
                    "BANKACCTTO", "CCACCTTO" -> inAccountTo = false
                    "LEDGERBAL" -> inLedgerBalance = false
                }
            }
        }
        flushStmt()
        return statements
    }

    private class StatementBuilder(val isCreditCard: Boolean) {
        val account = mutableMapOf<String, String>()
        val balance = mutableMapOf<String, String>()
        var currency: String? = null
        val transactions = mutableListOf<OfxTransaction>()

        fun build(): OfxStatement {
            val acctId = account["ACCTID"] ?: "unknown"
            val bankId = account["BANKID"]
            return OfxStatement(
                accountKey = AccountKeys.of(bankId, acctId),
                bankId = bankId,
                accountId = acctId,
                accountType = account["ACCTTYPE"],
                isCreditCard = isCreditCard,
                currency = currency,
                ledgerBalance = balance["BALAMT"]?.let(Money::parse),
                balanceDate = balance["DTASOF"]?.let(::parseDate),
                transactions = transactions.toList(),
            )
        }
    }

    private fun buildTxn(t: Map<String, String>): OfxTransaction? {
        val date = (t["DTPOSTED"] ?: t["DTUSER"])?.let(::parseDate) ?: return null
        val amount = t["TRNAMT"]?.let(Money::parse) ?: return null
        val name = t["NAME"] ?: t["PAYEEID"] ?: ""
        return OfxTransaction(
            fitId = t["FITID"],
            type = t["TRNTYPE"],
            date = date,
            amount = amount,
            name = name,
            memo = t["MEMO"].orEmpty(),
            checkNumber = t["CHECKNUM"],
        )
    }

    /** OFX dates look like 20261015, 20261015120000 or 20261015120000.000[-5:EST]. */
    fun parseDate(s: String): LocalDate? {
        val digits = s.trim().take(8)
        if (digits.length < 8 || !digits.all { it.isDigit() }) return null
        return runCatching {
            LocalDate(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
        }.getOrNull()
    }

    private fun decodeEntities(s: String): String =
        if ('&' !in s) s else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
}
