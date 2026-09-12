package com.github.kr328.clash.service.clash.module

import org.junit.Assert.*
import org.junit.Test

class NetworkRouteFingerprintTest {
    private val wifi = NetworkRouteFingerprint("wlan0", setOf("192.0.2.10/24"), setOf("default:192.0.2.1"), 1500, null)

    @Test fun additionalAddressDoesNotCloseExistingConnections() {
        assertFalse(routeRequiresRecovery(wifi, wifi.copy(linkAddresses = wifi.linkAddresses + "2001:db8::10/64")))
    }

    @Test fun lostSourceAddressOrChangedGatewayRequiresRecovery() {
        assertTrue(routeRequiresRecovery(wifi, wifi.copy(linkAddresses = setOf("192.0.2.11/24"))))
        assertTrue(routeRequiresRecovery(wifi, wifi.copy(routes = setOf("default:192.0.2.2"))))
        assertTrue(routeRequiresRecovery(wifi, null))
    }

    @Test fun unchangedRouteDoesNotRequireRecovery() {
        assertFalse(routeRequiresRecovery(wifi, wifi.copy()))
    }
}
