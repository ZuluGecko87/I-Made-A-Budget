package com.z.imab

import com.z.imab.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyEvalTest {
    @Test
    fun plainAmountsStillParse() {
        assertEquals(123456, Money.eval("1,234.56"))
        assertEquals(-1200, Money.eval("(12.00)"))
        assertEquals(550, Money.eval("$5.5"))
        assertFalse(Money.isExpression("-12.00"))
        assertFalse(Money.isExpression("1,234.56"))
    }

    @Test
    fun arithmetic() {
        assertEquals(12500, Money.eval("120.00 + 5.00"))
        assertEquals(12500, Money.eval("120+5"))
        assertEquals(11500, Money.eval("120 - 5"))
        assertEquals(12000, Money.eval("40*3"))
        assertEquals(12000, Money.eval("40x3"))
        assertEquals(3750, Money.eval("(60 + 15) / 2"))
        assertEquals(3333, Money.eval("100/3"))
        assertEquals(101000, Money.eval("1,000 + 10"))
        assertEquals(-500, Money.eval("-10 + 5"))
        assertTrue(Money.isExpression("120 + 5"))
    }

    @Test
    fun leadingOperatorAppliesToTheCurrentAmount() {
        assertEquals(12500, Money.eval("+5", current = 12000))
        assertEquals(11000, Money.eval("-10", current = 12000))
        assertEquals(24000, Money.eval("*2", current = 12000))
        assertEquals(6000, Money.eval("/2", current = 12000))
        assertEquals(-1000, Money.eval("-10"), "without a current amount it's just a negative number")
        assertEquals(500, Money.eval("+5"))
    }

    @Test
    fun nonsenseIsRejected() {
        assertNull(Money.eval("120 +"))
        assertNull(Money.eval("10 / 0"))
        assertNull(Money.eval("(5 + 2"))
        assertNull(Money.eval("five"))
        assertNull(Money.eval("5 ++ "))
    }
}
