package com.obdscanner.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {

    @Test
    fun decodesReadinessMonitors() {
        // MIL on, 2 codes; misfire/fuel/components available + complete; catalyst & EVAP incomplete.
        val s = MonitorStatus.decode(0x82, 0x07, 0x65, 0x05)
        assertTrue(s.milOn)
        assertEquals(2, s.dtcCount)
        assertFalse(s.compressionIgnition)
        assertEquals(
            listOf("Misfire", "Fuel system", "Comprehensive components", "Catalyst", "Evaporative system", "Oxygen sensor", "Oxygen sensor heater"),
            s.monitors.map { it.name },
        )
        assertEquals(listOf("Catalyst", "Evaporative system"), s.incompleteMonitors.map { it.name })
        assertFalse(s.likelyPassesInspection)
    }

    @Test
    fun decodesDieselMonitors() {
        val s = MonitorStatus.decode(0x00, 0x0F, 0xC1, 0x40)
        assertTrue(s.compressionIgnition)
        assertEquals(listOf("Particulate filter"), s.incompleteMonitors.map { it.name })
        assertTrue(s.likelyPassesInspection)
    }

    @Test
    fun readinessResetsAfterClearingCodes() = runBlocking {
        val client = ObdClient(SimulatedElmChannel(latencyMs = 0))
        assertTrue(client.monitorStatus()!!.incompleteMonitors.isEmpty())
        client.clearDtcs()
        val after = client.monitorStatus()!!
        assertFalse(after.milOn)
        assertEquals(listOf("Catalyst", "Evaporative system", "Oxygen sensor"), after.incompleteMonitors.map { it.name })
    }

    @Test
    fun readsFreezeFrame() = runBlocking {
        val client = ObdClient(SimulatedElmChannel(latencyMs = 0))
        val frame = client.readFreezeFrame()!!
        assertEquals("P0133", frame.dtc)
        assertTrue(frame.values.getValue(Pids.RPM) in 600.0..4000.0)
        assertTrue(Pids.FUEL_PRESSURE !in frame.values)
        client.clearDtcs()
        assertNull(client.readFreezeFrame())
    }

    @Test
    fun decodesYearAndMakeFromVin() {
        assertEquals(VehicleIdentity(2013, "Chevrolet"), VehicleIdentity.fromVin("1GNKVJKD5DJ123456"))
        assertEquals(VehicleIdentity(2003, "Honda"), VehicleIdentity.fromVin("1HGCM82633A004352"))
        assertEquals(VehicleIdentity(1998, "Ford"), VehicleIdentity.fromVin("1FTZR15V8WTA12345"))
        assertEquals("2013 Chevrolet", VehicleIdentity.fromVin("1GNKVJKD5DJ123456").label)
        assertNull(VehicleIdentity.fromVin("short").label)
    }

    @Test
    fun convertsToImperial() {
        val coolant = Units.display(Pids.COOLANT_TEMP, 90.0, UnitSystem.IMPERIAL)
        assertEquals("194", coolant.text)
        assertEquals("°F", coolant.unit)
        assertEquals("62", Units.display(Pids.SPEED, 100.0, UnitSystem.IMPERIAL).text)
        assertEquals("14.6", Units.display(Pids.BARO_PRESSURE, 101.0, UnitSystem.IMPERIAL).text)
        assertEquals("km/h", Units.display(Pids.SPEED, 100.0, UnitSystem.METRIC).unit)
    }
}
