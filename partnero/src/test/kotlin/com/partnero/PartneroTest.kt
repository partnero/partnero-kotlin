package com.partnero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartneroTest {
    @Test
    fun `builds RevenueCat attributes`() {
        assertEquals(mapOf("partnero_code" to "PARTNER1"), Partnero.revenueCatAttributes(" PARTNER1 "))
        assertTrue(Partnero.revenueCatAttributes("  ").isEmpty())
    }

    @Test
    fun `exposes the library version`() {
        assertTrue(Partnero.VERSION.matches(Regex("""\d+\.\d+\.\d+.*""")))
    }
}
