package com.noop.analytics

import org.junit.Assert.*
import org.junit.Test

class EnergyBalanceTest {
    @Test fun deficitSurplusAndMissingDataMatchSwiftOracle() {
        val cases = listOf(null to 2300.0, 2000.0 to null, 0.0 to 2000.0, 1800.0 to 2300.0,
            2700.0 to 2300.0, 2300.0 to 2300.0, -1.0 to 2300.0, 2000.0 to 0.0,
            Double.NaN to 2300.0, 2000.0 to Double.POSITIVE_INFINITY)
        // Verbatim output of the standalone Swift EnergyBalance helper over these cases.
        val expected = listOf(null, null, -2000.0, -500.0, 400.0, 0.0, null, null, null, null)
        assertEquals(expected, cases.map { EnergyBalance.net(it.first, it.second) })
        assertEquals("null,null,-2000.0,-500.0,400.0,0.0,null,null,null,null",
            cases.joinToString(",") { EnergyBalance.net(it.first, it.second)?.let { value -> "%.1f".format(java.util.Locale.US, value) } ?: "null" })
    }
}
