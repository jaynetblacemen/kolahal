package com.mtwabpin.kolahal.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {
    private fun conv(s: String): String? = Units.parse(s)?.let { (a, f, t) -> Units.convert(a, f, t) }

    @Test fun length() = assertEquals("5 km = 3.1069 mi", conv("5 km in mi"))
    @Test fun temperature() = assertEquals("100 °C = 212 °F", conv("100 c to f"))
    @Test fun kelvin() = assertEquals("0 K = -273.15 °C", conv("0 k in c"))
    @Test fun mass() = assertEquals("1 kg = 2.2046 lb", conv("1 kg in lb"))
    @Test fun data() = assertEquals("1 GiB = 1,073.7418 MB", conv("1 gib in mb"))
    @Test fun mismatchedDimensions() = assertNull(conv("5 km in kg"))
    @Test fun currencyIsParsedButNotConverted() {
        assertEquals(Triple(100.0, "usd", "inr"), Units.parse("100 usd in inr"))
        assertNull(conv("100 usd in inr"))
    }
    @Test fun notAConversion() = assertNull(Units.parse("2^10 / 4"))
    @Test fun decimalCommaAndThousands() {
        assertEquals(1.5, Units.number("1,5")!!, 1e-9)
        assertEquals(1000.0, Units.number("1,000")!!, 1e-9)
        assertEquals(1234567.5, Units.number("1,234,567.5")!!, 1e-9)
        assertNull(Units.number("1,5,3"))
        assertEquals(1.5, Units.parse("1,5 kg in lb")!!.first, 1e-9)
    }
}
