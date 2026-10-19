package com.z.imab.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

data class YearMonth(val year: Int, val month: Int) : Comparable<YearMonth> {
    init {
        require(month in 1..12) { "month must be 1..12" }
    }

    operator fun plus(months: Int): YearMonth {
        val index = year * 12 + (month - 1) + months
        return YearMonth(index.floorDiv(12), index.mod(12) + 1)
    }

    operator fun minus(months: Int): YearMonth = plus(-months)

    val firstDay: LocalDate get() = LocalDate(year, month, 1)
    val lastDay: LocalDate get() = firstDay.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)

    val label: String get() = "${NAMES[month - 1]} $year"

    override fun compareTo(other: YearMonth): Int =
        compareValuesBy(this, other, { it.year }, { it.month })

    override fun toString(): String = "$year-${month.toString().padStart(2, '0')}"

    companion object {
        private val NAMES = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )

        fun of(date: LocalDate) = YearMonth(date.year, date.monthNumber)

        fun parse(s: String): YearMonth {
            val (y, m) = s.split("-")
            return YearMonth(y.toInt(), m.toInt())
        }
    }
}
