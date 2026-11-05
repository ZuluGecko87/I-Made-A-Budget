package com.z.imab

import com.z.imab.budget.Amortizer
import com.z.imab.budget.LumpSum
import com.z.imab.budget.PayoffPlan
import com.z.imab.model.YearMonth
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Amortization schedules and what extra payments do to them. */
class AmortizationTest {
    private val start = YearMonth(2027, 1)

    /** $300,000 at 6% over 30 years: $1,798.65 a month, rounded up a cent so the last payment is the smaller one. */
    private fun base(plan: PayoffPlan = PayoffPlan()) = Amortizer.schedule(30_000_000, 6.0, 179_866, 40_000, start, plan)!!

    @Test
    fun aThirtyYearMortgageTakesThirtyYears() {
        val s = base()
        assertEquals(360, s.months)
        assertEquals(YearMonth(2056, 12), s.payoff)
        // Published tables give $347,514.57; a few cents' rounding per month is fine.
        assertTrue(abs(s.totalInterest - 34_751_457) < 1_000, "interest ${s.totalInterest}")
        assertEquals(30_000_000, s.totalPrincipal)
        assertEquals(0, s.rows.last().balance)
        // First payment: $1,500 interest, $298.65 principal, plus escrow.
        val first = s.rows.first()
        assertEquals(150_000, first.interest)
        assertEquals(29_866, first.principal)
        assertEquals(219_866, first.payment)
        // Escrow every month.
        assertEquals(360L * 40_000, s.totalEscrow)
    }

    @Test
    fun extraEachMonthPaysItOffSoonerForLessInterest() {
        val plain = base()
        val extra = base(PayoffPlan(extraMonthly = 20_000))
        assertTrue(extra.months < plain.months - 48, "${extra.months} months")
        assertTrue(extra.totalInterest < plain.totalInterest - 5_000_000)
        assertEquals(30_000_000, extra.totalPrincipal)
        assertEquals(20_000, extra.rows.first().extra)
    }

    @Test
    fun aLumpSumComesOffInItsMonth() {
        val s = base(PayoffPlan(lumpSums = listOf(LumpSum(YearMonth(2027, 6), 1_000_000))))
        val june = s.rows.single { it.month == YearMonth(2027, 6) }
        assertEquals(1_000_000, june.extra)
        val before = base().rows.single { it.month == YearMonth(2027, 6) }
        assertEquals(before.balance - 1_000_000, june.balance + (june.principal - june.extra - before.principal))
        assertTrue(s.months < 360)
    }

    @Test
    fun extrasCanStartLaterOrComeOnceAYear() {
        val later = base(PayoffPlan(extraMonthly = 10_000, extraFrom = YearMonth(2030, 1)))
        assertEquals(0, later.rows.first().extra)
        assertEquals(10_000, later.rows.single { it.month == YearMonth(2030, 1) }.extra)
        val yearly = base(PayoffPlan(yearlyExtra = 500_000, yearlyMonth = 4))
        assertEquals(listOf(500_000L), yearly.rows.filter { it.month.year == 2027 }.map { it.extra }.filter { it > 0 })
        assertTrue(yearly.years.first().extra == 500_000L)
    }

    @Test
    fun theLastPaymentIsOnlyWhatsLeft() {
        val s = base(PayoffPlan(lumpSums = listOf(LumpSum(start, 50_000_000))))
        assertEquals(1, s.months)
        assertEquals(30_000_000, s.rows.single().principal)
        assertEquals(0, s.rows.single().balance)
    }

    @Test
    fun aPaymentBelowTheInterestNeverPaysOff() {
        assertNull(Amortizer.schedule(30_000_000, 6.0, 150_000, 0, start))
    }
}
