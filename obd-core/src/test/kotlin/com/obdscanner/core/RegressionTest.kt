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
}
