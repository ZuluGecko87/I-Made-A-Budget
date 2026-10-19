package com.z.imab.importer

import com.z.imab.model.BudgetData
import com.z.imab.model.PayeeRule

/** Finds the auto-categorize rule for a bank payee. Only explicit rules are used; nothing is guessed. */
object RuleBook {
    /** The text a new rule should match for this payee, e.g. "MAIN STREET MORTGAGE 1234" -> "main street mortgage". */
    fun suggestedMatch(payee: String): String = ImportMatcher.normalizePayee(payee)

    fun matches(rule: PayeeRule, payee: String): Boolean {
        val p = " ${ImportMatcher.normalizePayee(payee)} "
        val m = ImportMatcher.normalizePayee(rule.match)
        return m.isNotEmpty() && p.contains(" $m ")
    }

    /** The matching rule with the most specific (longest) text. */
    fun find(data: BudgetData, payee: String): PayeeRule? =
        data.rules.values.filter { matches(it, payee) }.maxByOrNull { it.match.length }

    fun describe(data: BudgetData, rule: PayeeRule): String = buildString {
        append(
            when {
                rule.transferAccountId != null -> "Transfer: ${data.accountName(rule.transferAccountId)}"
                rule.categoryId != null -> data.categoryName(rule.categoryId)
                else -> "Uncategorized"
            }
        )
        rule.loanAccountId?.let { append(" · payment on ${data.accountName(it)}") }
    }
}
