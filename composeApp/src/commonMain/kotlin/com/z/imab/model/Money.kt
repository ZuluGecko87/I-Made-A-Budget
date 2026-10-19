package com.z.imab.model

import kotlin.math.abs

object Money {
    fun format(cents: Long, symbol: Boolean = true): String {
        val negative = cents < 0
        val a = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else abs(cents)
        val dollars = (a / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val body = (if (symbol) "$" else "") + dollars + "." + (a % 100).toString().padStart(2, '0')
        return if (negative) "-$body" else body
    }

    /** Plain editable form, e.g. 123456 -> "1234.56". */
    fun toInput(cents: Long): String {
        val a = abs(cents)
        val s = "${a / 100}.${(a % 100).toString().padStart(2, '0')}"
        return if (cents < 0) "-$s" else s
    }

    /**
     * Parses user or bank input such as "1,234.56", "-12", "$5.5", "(12.00)" or "12,34".
     * Returns cents, or null if it isn't a number.
     */
    fun parse(input: String): Long? {
        var s = input.trim().replace("$", "").replace(" ", "").replace(" ", "")
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        when {
            s.startsWith("-") -> { negative = !negative; s = s.substring(1) }
            s.startsWith("+") -> s = s.substring(1)
        }
        if (s.endsWith("-")) { negative = !negative; s = s.dropLast(1) }
        if (s.isEmpty()) return null

        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val decimal = when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) '.' else ','
            lastComma >= 0 && s.count { it == ',' } == 1 && s.length - lastComma - 1 in 1..2 -> ','
            else -> '.'
        }
        s = s.replace(if (decimal == '.') "," else ".", "")
        val parts = s.split(decimal)
        if (parts.size > 2) return null
        val whole = parts[0].ifEmpty { "0" }
        val frac = parts.getOrElse(1) { "" }
        if (!whole.all { it.isDigit() } || !frac.all { it.isDigit() }) return null
        var cents = (whole.toLongOrNull() ?: return null) * 100 + (frac + "00").take(2).toLong()
        if (frac.length > 2 && frac[2] >= '5') cents += 1
        return if (negative) -cents else cents
    }
}
