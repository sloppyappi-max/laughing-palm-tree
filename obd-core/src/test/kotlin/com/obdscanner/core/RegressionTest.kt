package com.obdscanner.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bugs found in review; each test failed before its fix. */
class RegressionTest {

    @Test
    fun olderCarsNeverGetCanHeaderCommands() = runBlocking {
        val sim = SimulatedElmChannel(latencyMs = 0, protocol = "ISO 9141-2")
        val client = ObdClient(sim)
        val info = client.initialize()
        assertTrue(info.vehicleResponding)
        assertFalse(client.supportsModuleAccess)
        assertFalse(client.isCan)
        // Module discovery used to leave a "restore" pending that sent ATSH7DF here.
        assertNotNull(client.read(Pids.RPM))
        assertNotNull(client.monitorStatus())
        assertFalse(sim.sent.toString(), sim.sent.any { it.startsWith("ATSH") || it.startsWith("ATCF") || it.startsWith("ATFC") })
        // Mode 06 is decoded in the CAN format only.
        assertTrue(client.readMonitorTests().isEmpty())
    }

    @Test
    fun filtersAreRestoredWithUniversalCommandsAfterModuleAccess() = runBlocking {
        val sim = SimulatedElmChannel(latencyMs = 0)
        val client = ObdClient(sim).also { it.initialize() }
        client.scanModules()
        sim.sent.clear()
        assertNotNull(client.read(Pids.RPM))
        assertEquals(listOf("ATST32", "ATSH7DF", "ATFCSM0", "ATAR", "ATCF7E8", "ATCM7F8", "010C"), sim.sent)
        // Nothing left to restore on the next request.
        sim.sent.clear()
        client.read(Pids.SPEED)
        assertEquals(listOf("010D"), sim.sent)
        assertFalse(sim.sent.any { it.startsWith("ATCRA") })
    }

    @Test
    fun terminalCommandsCannotBreakTheOutputFormat() = runBlocking {
        val client = ObdClient(SimulatedElmChannel(latencyMs = 0)).also { it.initialize() }
        client.raw("AT H1") // would make every answer start with "7E8…"
        assertNotNull(client.read(Pids.RPM))
        client.raw("ATZ")
        assertNotNull(client.read(Pids.SPEED))
        assertEquals("12.6V", client.raw("ATRV").single())
    }

    @Test
    fun noRestoreTrafficWhenNothingChanged() = runBlocking {
        val sim = SimulatedElmChannel(latencyMs = 0)
        val client = ObdClient(sim).also { it.initialize() }
        sim.sent.clear()
        client.read(Pids.RPM)
        client.read(Pids.SPEED)
        assertEquals(listOf("010C", "010D"), sim.sent)
    }

    @Test
    fun fallsBackToExplicitCanProtocolWhenAutoSearchFails() = runBlocking {
        // Seen on a brand dongle: ATRV works, but automatic protocol search never finds the car.
        val sim = SimulatedElmChannel(latencyMs = 0, autoSearchWorks = false)
        val info = ObdClient(sim).initialize()
        assertTrue(info.vehicleResponding)
        assertTrue(sim.sent.contains("ATSP6"))
        assertTrue(info.protocol!!.contains("CAN 11/500"))
    }

    @Test
    fun reportsWhatTheAdapterSaidWhenTheCarDoesNotAnswer() = runBlocking {
        val sim = SimulatedElmChannel(latencyMs = 0, protocol = "ISO 15765-4 (CAN 11/500)", autoSearchWorks = false)
        // Simulate ignition off: every protocol fails.
        val client = ObdClient(object : ElmChannel by sim {
            override suspend fun exchange(command: String, timeoutMs: Long): String =
                if (command.startsWith("01")) "SEARCHING...\rUNABLE TO CONNECT\r\r" else sim.exchange(command, timeoutMs)
        })
        val info = client.initialize()
        assertFalse(info.vehicleResponding)
        assertEquals("SEARCHING... | UNABLE TO CONNECT", info.probeResponse)
        assertEquals("ATSP0", sim.sent.last { it.startsWith("ATSP") }) // left on automatic
    }

    @Test
    fun understandsAdaptersThatIgnoreHeadersOff() = runBlocking {
        val client = ObdClient(SimulatedElmChannel(latencyMs = 0, alwaysHeaders = true))
        val info = client.initialize()
        assertTrue(info.vehicleResponding)
        assertTrue(Pids.RPM.code in client.supportedPids())
        assertNotNull(client.read(Pids.RPM))
    }

    @Test
    fun stripsCanHeadersOnlyWhenTheLengthByteMatches() {
        assertEquals(listOf(0x41, 0x0D, 0x32), ElmResponseParser.stripCanHeader("7E803410D32")!!.map { it.toInt() and 0xFF })
        assertEquals(listOf(0x41, 0x0D, 0x32), ElmResponseParser.stripCanHeader("18DAF11003410D32")!!.map { it.toInt() and 0xFF })
        // Plain header-less data is left alone (even length, or length byte doesn't fit).
        assertEquals(null, ElmResponseParser.stripCanHeader("410C1AF8"))
        assertEquals(null, ElmResponseParser.stripCanHeader("7E8FF410D32"))
    }
}
