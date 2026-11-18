package com.obdscanner.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole app (ObdClient) running on a native-protocol Innova dongle. */
class InnovaNativeChannelTest {

    @Test
    fun appWorksThroughTheNativeProtocol() = runBlocking {
        val dongle = FakeInnovaDongle()
        val client = ObdClient(InnovaNativeChannel(dongle))
        val info = client.initialize()
        assertTrue(info.probeResponse, info.vehicleResponding)
        assertEquals("ISO 15765-4 (CAN 11/500)", info.protocol)
        assertTrue(client.supportsModuleAccess)
        assertEquals(listOf("Engine (ECM)"), info.ecus.map { it.name })

        assertTrue(Pids.RPM.code in client.supportedPids())
        assertNotNull(client.read(Pids.RPM))
        assertEquals("1HGCM82633A004352", client.readVin()) // multi-frame answer
        assertEquals(listOf("P0133", "P0420"), client.readDtcs(DtcType.STORED).map { it.code })
        assertEquals("P0133", client.readFreezeFrame()!!.dtc)
        assertEquals(4, client.readMonitorTests().size)
        assertNotNull(client.read(ManufacturerPids.GM.first().toPid())) // physical address 7E2

        // Every frame sent was AUTOLINK, WRITE or a read-only SYSTEM query.
        assertTrue(dongle.received.all { InnovaVci.isSafe(it) })
        val commands = dongle.received.map { it[5].toInt() and 0xFF }.toSet()
        assertTrue(commands.toString(), commands.all { it in setOf(InnovaVci.CMD_AUTOLINK, InnovaVci.CMD_WRITE) })
    }

    @Test
    fun allSystemScanFindsModulesThroughTheNativeProtocol() = runBlocking {
        val client = ObdClient(InnovaNativeChannel(FakeInnovaDongle())).also { it.initialize() }
        val scan = client.scanModules()
        val byId = scan.reports.associateBy { it.module.requestHex }
        assertTrue(byId.keys.toString(), byId.keys.containsAll(setOf("7E0", "7E2", "241")))
        assertEquals("C0035-5A", byId.getValue("241").dtcs.single().fullCode)
        assertNotNull(client.read(Pids.SPEED)) // normal requests still work afterwards
    }

    @Test
    fun noVehicleFailsFastInsteadOfRetryingForMinutes() = runBlocking {
        val dongle = FakeInnovaDongle(vehicleConnected = false)
        val start = System.currentTimeMillis()
        val info = ObdClient(InnovaNativeChannel(dongle)).initialize()
        assertFalse(info.vehicleResponding)
        // RS2 retries AUTOLINK up to 3 times when told "no vehicle"; the protocol search then
        // gets an immediate answer instead of new 20 s attempts.
        val autolinks = dongle.received.count { (it[5].toInt() and 0xFF) == InnovaVci.CMD_AUTOLINK }
        assertEquals(3, autolinks)
        assertTrue(System.currentTimeMillis() - start < 5_000)
    }

    @Test
    fun refusesToForwardDongleChangingFrames() = runBlocking {
        val channel = InnovaNativeChannel(FakeInnovaDongle())
        val reboot = byteArrayOf(0xAE.toByte(), 0x0F, 0x03, 0x00, 0x00, 0x07, 0x05)
        val error = runCatching { channel.exchangeBytes(reboot, 1_000) { true } }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
