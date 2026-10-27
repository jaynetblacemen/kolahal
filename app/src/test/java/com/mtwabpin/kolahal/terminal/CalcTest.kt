package com.mtwabpin.kolahal.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CalcTest {
    private fun a(text: String) = Calc.answer(text)

    @Test fun arithmetic() {
        assertEquals("1024", a("2^10"))
        assertEquals("52", a("17 * 3 + 1"))
        assertEquals("14", a("2 + 3 × 4"))
        assertEquals("20", a("(2 + 3) * 4"))
        assertEquals("2.5", a("5 ÷ 2"))
        assertEquals("-4", a("-2^2"))
        assertEquals("512", a("2^3^2"))
        assertEquals("0.5", a("2 ** -1"))
        assertEquals("12", a("3 x 4"))
        assertEquals("0.3", a("0.1 + 0.2"))
        assertEquals("1000", a("1e3"))
        assertEquals("-1", a("3 − 4"))
    }

    @Test fun functionsAndConstants() {
        assertEquals("1.41421356237", a("sqrt(2)"))
        assertEquals("3", a("sqrt 9"))
        assertEquals("3.14159265359", a("pi"))
        assertEquals("6.28318530718", a("2π"))
        assertEquals("27", a("3(4+5)"))
        assertEquals("120", a("5!"))
        assertEquals("0.5", a("sin 30°"))
        assertEquals("2", a("log 100"))
        assertEquals("7", a("max(1, 7, 3)"))
        assertEquals("3", a("round(2.5)"))
    }

    /** As on a phone's calculator. */
    @Test fun percents() {
        assertEquals("12", a("15% of 80"))
        assertEquals("92", a("80 + 15%"))
        assertEquals("68", a("80 - 15%"))
        assertEquals("0.15", a("15%"))
        assertEquals("1", a("10 % 3"))
        assertEquals("1", a("10 mod 3"))
    }

    @Test fun formatting() {
        assertEquals("0.333333333333", a("1/3"))
        assertEquals("1e+20", a("10^20"))
        assertEquals("0", a("1 - 1"))
    }

    /** Lua code, a conversion and words are someone else's to read. */
    @Test fun notArithmeticIsLeftAlone() {
        assertNull(a("math.sqrt(2)"))
        assertNull(a("os.date(\"%A\")"))
        assertNull(a("5 km in mi"))
        assertNull(a("2 +"))
        assertNull(a("hello"))
        assertNull(a(""))
    }

    @Test fun noAnswerSaysWhy() {
        assertEquals("can't divide by zero", assertThrows(ArithmeticException::class.java) { a("1/0") }.message)
        assertEquals("not a real number", assertThrows(ArithmeticException::class.java) { a("sqrt(-1)") }.message)
    }
}
