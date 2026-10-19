package com.z.imab.budget

import com.z.imab.model.Frequency
import com.z.imab.model.ScheduledTransaction
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

object Scheduler {
    /** The n-th occurrence (0-based) counted from [start]. */
    fun occurrence(start: LocalDate, frequency: Frequency, n: Int): LocalDate = when (frequency) {
        Frequency.ONCE -> start
        Frequency.WEEKLY -> start.plus(7 * n, DateTimeUnit.DAY)
        Frequency.EVERY_2_WEEKS -> start.plus(14 * n, DateTimeUnit.DAY)
        Frequency.EVERY_4_WEEKS -> start.plus(28 * n, DateTimeUnit.DAY)
        Frequency.MONTHLY -> start.plus(n, DateTimeUnit.MONTH)
        Frequency.EVERY_2_MONTHS -> start.plus(2 * n, DateTimeUnit.MONTH)
        Frequency.QUARTERLY -> start.plus(3 * n, DateTimeUnit.MONTH)
        Frequency.TWICE_A_YEAR -> start.plus(6 * n, DateTimeUnit.MONTH)
        Frequency.YEARLY -> start.plus(n, DateTimeUnit.YEAR)
    }

    /** First occurrence strictly after [date], or null if the schedule is finished. */
    fun nextAfter(s: ScheduledTransaction, date: LocalDate): LocalDate? {
        if (s.frequency == Frequency.ONCE) return null
        var n = 0
        while (n < 100_000) {
            val d = occurrence(s.startDate, s.frequency, n)
            if (d > date) return d.takeIf { s.endDate == null || it <= s.endDate }
            n++
        }
        return null
    }

    /** Occurrences from [s.nextDate] onward that fall in [from]..[to]. */
    fun occurrencesBetween(s: ScheduledTransaction, from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var d: LocalDate? = s.nextDate
        while (d != null && d <= to && out.size < 1000) {
            if (d >= from) out += d
            d = nextAfter(s, d)
        }
        return out
    }

    /** Deterministic id so two devices entering the same occurrence produce the same transaction. */
    fun occurrenceTxnId(scheduledId: String, date: LocalDate) = "sched-$scheduledId-$date"
}
