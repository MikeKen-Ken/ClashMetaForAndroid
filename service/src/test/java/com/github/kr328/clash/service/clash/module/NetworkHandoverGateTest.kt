package com.github.kr328.clash.service.clash.module

import org.junit.Assert.*
import org.junit.Test

class NetworkHandoverGateTest {
    @Test fun waitsForStablePreferredNetwork() {
        val gate = NetworkHandoverGate<String>()
        assertEquals("mobile", gate.choose("mobile", "wifi", true, 0))
        assertEquals("mobile", gate.choose("mobile", "wifi", true, 1_999))
        assertEquals("wifi", gate.choose("mobile", "wifi", true, 2_000))
        assertNull(gate.remaining(2_000))
    }

    @Test fun neverWaitsOnLostOrBlockedNetwork() {
        val gate = NetworkHandoverGate<String>()
        gate.choose("mobile", "wifi", true, 0)
        assertEquals("wifi", gate.choose("mobile", "wifi", false, 10))
        assertNull(gate.choose("wifi", null, false, 20))
    }

    @Test fun fluctuationRestartsStabilityWindow() {
        val gate = NetworkHandoverGate<String>()
        gate.choose("mobile", "wifi", true, 0)
        assertEquals("mobile", gate.choose("mobile", "mobile", true, 1_000))
        assertEquals("mobile", gate.choose("mobile", "wifi", true, 2_000))
        assertEquals("mobile", gate.choose("mobile", "wifi", true, 3_999))
        assertEquals("wifi", gate.choose("mobile", "wifi", true, 4_000))
    }
}
