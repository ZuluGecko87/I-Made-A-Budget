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

    private const val OPERATORS = "+-*/×÷x"

    /** True when [input] is arithmetic (like "120 + 5") rather than a single amount. */
    fun isExpression(input: String): Boolean {
        val s = input.replace(" ", "")
        return s.drop(1).any { it in OPERATORS } && parse(s) == null
    }

    /**
     * Works out an amount typed as arithmetic: "120.00 + 5", "40*3", "(60 + 15) / 2".
     * With [current], an amount that starts with an operator applies to it, so "+5" adds five
     * and "-20" takes twenty off. A plain amount ("1,234.56", "(12.00)") parses as usual.
     * Multiplying and dividing are by plain numbers ("x2", "/3"); the result is rounded to cents.
     * Returns cents, or null if it can't be worked out.
     */
    fun eval(input: String, current: Long? = null): Long? {
        val s = input.trim().replace(" ", "").replace(" ", "").replace("$", "")
        if (s.isEmpty()) return null
        if (current != null && s[0] in OPERATORS) return eval(toInput(current) + s)
        parse(s)?.let { return it }
        val dollars = Calc(s).run() ?: return null
        if (dollars.isNaN() || dollars.isInfinite() || kotlin.math.abs(dollars) > 1e13) return null
        return kotlin.math.round(dollars * 100).toLong()
    }

    /** Recursive-descent calculator over dollar amounts. */
    private class Calc(val s: String) {
        var i = 0

        fun run(): Double? = expr()?.takeIf { i == s.length }

        private fun expr(): Double? {
            var v = term() ?: return null
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term() ?: return null
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Double? {
            var v = factor() ?: return null
            while (i < s.length && s[i] in "*/×÷x") {
                val op = s[i++]
                val r = factor() ?: return null
                v = if (op == '/' || op == '÷') { if (r == 0.0) return null; v / r } else v * r
            }
            return v
        }

        private fun factor(): Double? {
            if (i >= s.length) return null
            return when (s[i]) {
                '-' -> { i++; factor()?.let { -it } }
                '+' -> { i++; factor() }
                '(' -> {
                    i++
                    val v = expr() ?: return null
                    if (i >= s.length || s[i] != ')') return null
                    i++
                    v
                }
                else -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
                    if (i == start) return null
                    parse(s.substring(start, i))?.let { it / 100.0 }
                }
            }
        }
    }
}
