package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LanPortTest {
    @Test fun pickSkipsCurrentBusyAndWellKnownPorts() {
        val port = LanPort.pick(
            current = 7890,
            candidates = sequenceOf(7890, 21, 40000, 40001),
        ) { it != 40000 }

        assertEquals(40001, port)
    }

    @Test fun pickReturnsNullWhenEveryCandidateIsRejected() {
        val port = LanPort.pick(
            current = 20000,
            candidates = sequenceOf(20000, 7890, 30000),
        ) { false }

        assertNull(port)
    }

    @Test fun pickRandomStillChoosesWhenEveryBindCheckFails() {
        val port = LanPort.pickRandom(7890, Random(1)) { false }

        assertNotNull(port)
        assertTrue(port!! in LanPort.MIN..LanPort.MAX)
        assertTrue(port != LanPort.WELL_KNOWN)
    }
}
