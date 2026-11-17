package com.obdscanner.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedTest {

    private val client = ObdClient(SimulatedElmChannel(latencyMs = 0))

    @Test
    fun discoversEcusDuringInit() = runBlocking {
        val info = client.initialize()
        assertEquals(listOf("Engine (ECM)", "Transmission (TCM)"), info.ecus.map { it.name })
        // Headers must be off again afterwards, or normal parsing breaks.
        assertNotNull(client.read(Pids.RPM))
    }

    @Test
    fun readsMonitorTests() = runBlocking {
        val tests = client.readMonitorTests()
        assertEquals(4, tests.size)
        val o2 = tests.first { it.mid == 0x01 && it.tid == 0x01 }
        assertEquals("Oxygen sensor B1S1", o2.monitorName)
        assertEquals("Rich-to-lean threshold voltage", o2.testName)
        assertEquals("mV", o2.unit)
        assertEquals(450.2, o2.value, 0.1)
        assertTrue(o2.passed)
        val evap = tests.single { it.mid == 0x3C }
        assertEquals("EVAP leak (0.020\")", evap.monitorName)
        assertFalse(evap.passed)
    }

    @Test
    fun readsVehicleInfo() = runBlocking {
        val info = client.readVehicleInfo()
        assertEquals("1HGCM82633A004352", info.vin)
        assertEquals(listOf("12653917"), info.calibrationIds)
        assertEquals(listOf("1A2B3C4D"), info.cvns)
        assertEquals(listOf("ECM-EngineControl"), info.ecuNames)
    }

    @Test
    fun readsExtendedAndStatusPids() = runBlocking {
        val supported = client.supportedPids()
        assertTrue(Pids.ODOMETER.code in supported) // needs the 0x60..0xA0 ranges
        assertEquals(84213.0, client.read(Pids.ODOMETER)!!, 0.01)
        val fuelSystem = client.read(Pids.FUEL_SYSTEM_STATUS)!!
        assertTrue(Pids.FUEL_SYSTEM_STATUS.format(fuelSystem).contains("loop"))
        assertEquals("Gasoline", Pids.FUEL_TYPE_PID.format(client.read(Pids.FUEL_TYPE_PID)!!))
        assertEquals(1.0, client.read(Pids.COMMANDED_LAMBDA)!!, 0.001)
    }

    @Test
    fun everyPidHasAUniqueCode() {
        assertEquals(Pids.ALL.size, Pids.ALL.map { it.code }.toSet().size)
        assertTrue(Pids.ALL.size > 80)
    }

    @Test
    fun computesFuelEconomy() {
        val trip = TripComputer()
        // 100 km/h burning 8 L/h for one simulated hour in 1-second steps → 8 L/100 km.
        for (s in 0..3600) trip.update(100.0, null, 8.0, s * 1000L)
        assertEquals(100.0, trip.distanceKm, 0.1)
        assertEquals(8.0, trip.averageL100km!!, 0.01)
        assertEquals(29.4, TripComputer.l100kmToMpg(8.0), 0.1)
        assertEquals(8.0, trip.instantL100km!!, 0.01)
        trip.update(0.0, null, 0.8, 3_601_000L)
        assertNull(trip.instantL100km) // stopped
        assertEquals(1.64, TripComputer.fuelFromMaf(5.0), 0.01)
    }

    @Test
    fun convertsAlertLimitsBackToMetric() {
        for (pid in Pids.ALL.filter { it.label == null }) {
            val shown = Units.display(pid, 42.0, UnitSystem.IMPERIAL).value
            assertEquals(pid.name, 42.0, Units.toMetric(pid, shown, UnitSystem.IMPERIAL), 1e-6)
        }
        assertEquals(110.0, Units.toMetric(Pids.COOLANT_TEMP, 230.0, UnitSystem.IMPERIAL), 0.01)
    }

    @Test
    fun showsStatusPidsAsWords() {
        val d = Units.display(Pids.FUEL_SYSTEM_STATUS, 2.0, UnitSystem.IMPERIAL)
        assertEquals("Closed loop", d.text)
        assertEquals("", d.unit)
        assertEquals("148", Units.display(Pids.REFERENCE_TORQUE, 200.0, UnitSystem.IMPERIAL).text)
    }
}
