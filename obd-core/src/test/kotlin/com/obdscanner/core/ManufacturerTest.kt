package com.obdscanner.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManufacturerTest {

    private suspend fun connected(): ObdClient =
        ObdClient(SimulatedElmChannel(latencyMs = 0)).also { it.initialize() }

    @Test
    fun evaluatesFormulas() {
        val bytes = intArrayOf(0x1A, 0xF8, 0xFF, 0x80)
        assertEquals(1726.0, Formula.parse("(A*256+B)/4").evaluate(bytes), 1e-9)
        assertEquals(-14.0, Formula.parse("a - 40").evaluate(bytes), 1e-9)
        assertEquals(-1.0, Formula.parse("SIGNED(C)").evaluate(bytes), 1e-9)
        assertEquals(-128.0, Formula.parse("SIGNED(D)").evaluate(bytes), 1e-9)
        assertEquals(-32768.0, Formula.parse("SIGNED16(D,0)").evaluate(bytes), 1e-9)
        assertEquals(1.0, Formula.parse("BIT(A,1)").evaluate(bytes), 1e-9)
        assertEquals(-3.5, Formula.parse("-(1+2.5)").evaluate(bytes), 1e-9)
        assertEquals(4, Formula.parse("D*2").bytesNeeded)
    }

    @Test
    fun rejectsBadFormulas() {
        for (bad in listOf("A+", "(A", "X*2", "SIGNED(A,B)", "A $ 2", "")) {
            assertNull(bad, Formula.parseOrNull(bad))
        }
    }

    @Test
    fun readsManufacturerSensorAndReturnsToNormal() = runBlocking {
        val client = connected()
        assertTrue(client.supportsModuleAccess)
        val tft = ManufacturerPids.GM.first().toPid()
        assertTrue(tft.isCustom)
        assertEquals(CustomSensor.CUSTOM_BASE + 0x8001, tft.code)
        val value = client.read(tft)
        assertNotNull(value)
        assertTrue(value!! in 25.0..85.0) // the simulated transmission warms up from ~30 °C
        // Standard requests must work again right after (header restored to 7DF).
        assertNotNull(client.read(Pids.RPM))
        assertEquals(listOf("P0133", "P0420"), client.readDtcs(DtcType.STORED).map { it.code })
    }

    @Test
    fun customSensorWithTwoBytes() = runBlocking {
        val client = connected()
        val sensor = CustomSensor(1, "Test", "Test", "rpm", "7E2", "221941", "(A*256+B)/4")
        assertEquals(200.0, client.read(sensor.toPid())!!, 1e-9)
        // Unknown DID → negative response → null, not a crash.
        assertNull(client.read(sensor.copy(command = "22BEEF").toPid()))
    }

    @Test
    fun scansAllModules() = runBlocking {
        val client = connected()
        var lastProgress = 0f
        val scan = client.scanModules { lastProgress = it }
        assertEquals(1f, lastProgress)
        val byId = scan.reports.associateBy { it.module.requestHex }
        assertEquals(setOf("7E0", "7E2", "241", "247"), byId.keys)
        assertEquals(listOf("P0133", "P0420"), byId.getValue("7E0").dtcs.map { it.code })
        assertTrue(byId.getValue("7E2").dtcs.isEmpty())
        assertNull(byId.getValue("7E2").problem)
        val abs = byId.getValue("241").dtcs.single()
        assertEquals("C0035-5A", abs.fullCode)
        assertEquals(DtcProtocol.UDS, abs.protocol)
        val airbag = byId.getValue("247").dtcs.single()
        assertEquals("B0001", airbag.code)
        assertEquals(DtcProtocol.GMLAN, airbag.protocol)
        assertTrue(scan.log.contains("found 241 -> 641"))
        // Back to normal afterwards.
        assertNotNull(client.read(Pids.SPEED))
    }

    @Test
    fun exploresDataIdentifiers() = runBlocking {
        val client = connected()
        val found = client.exploreDids(0x7E2, 0x1930, 0x1950)
        assertEquals(setOf(0x1940, 0x1941), found.keys)
        assertEquals(listOf(0x03, 0x20), found.getValue(0x1941).map { it.toInt() and 0xFF })
    }

    @Test
    fun validatesRequests() {
        assertTrue(runCatching { PidRequest("7E2", "221940") }.isSuccess)
        assertTrue(runCatching { PidRequest("7E", "221940") }.isFailure)
        assertTrue(runCatching { PidRequest("7E2", "22194") }.isFailure)
    }
}
