package com.obdscanner.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ObdClientTest {

    private val client = ObdClient(SimulatedElmChannel(latencyMs = 0))

    @Test
    fun initializesAgainstSimulator() = runBlocking {
        val info = client.initialize()
        assertEquals("ELM327 v1.5", info.elmVersion)
        assertEquals("ISO 15765-4 (CAN 11/500)", info.protocol)
        assertEquals(12.6, info.batteryVoltage!!, 0.001)
        assertTrue(info.vehicleResponding)
    }

    @Test
    fun readsSupportedPidsAcrossRanges() = runBlocking {
        val supported = client.supportedPids()
        assertTrue(0x0C in supported)
        assertTrue(0x42 in supported) // needs the 0x40 range
        assertTrue(0x5C in supported)
        assertFalse(0x0A in supported)
    }

    @Test
    fun readsLiveValues() = runBlocking {
        val rpm = client.read(Pids.RPM)
        assertNotNull(rpm)
        assertTrue(rpm!! in 600.0..4000.0)
        assertNull(client.read(Pids.FUEL_PRESSURE))
    }

    @Test
    fun readsAndClearsCodes() = runBlocking {
        client.monitorStatus()!!.let { assertTrue(it.milOn); assertEquals(2, it.dtcCount) }
        assertEquals(listOf("P0133", "P0420"), client.readDtcs(DtcType.STORED).map { it.code })
        assertEquals(listOf("P0301"), client.readDtcs(DtcType.PENDING).map { it.code })
        assertTrue(client.readDtcs(DtcType.PERMANENT).isEmpty())
        assertTrue(client.clearDtcs())
        assertTrue(client.readDtcs(DtcType.STORED).isEmpty())
        client.monitorStatus()!!.let { assertFalse(it.milOn); assertEquals(0, it.dtcCount) }
    }

    @Test
    fun readsVin() = runBlocking {
        assertEquals("1HGCM82633A004352", client.readVin())
    }

    @Test
    fun streamChannelReadsUntilPrompt() = runBlocking {
        // Like a real adapter: stale bytes are waiting, and the answer only appears after the command is written.
        var answer: ByteArrayInputStream = ByteArrayInputStream("stale>".toByteArray())
        val input = object : InputStream() {
            override fun read() = answer.read()
            override fun available() = answer.available()
        }
        val out = object : ByteArrayOutputStream() {
            override fun flush() { answer = ByteArrayInputStream("41 0D 32\r\r>".toByteArray()) }
        }
        val channel = StreamElmChannel(input, out)
        assertEquals("41 0D 32\r\r\r", channel.exchange("010D"))
        assertEquals("010D\r", out.toString())
    }

    @Test(expected = ElmTimeoutException::class)
    fun streamChannelTimesOut() {
        runBlocking {
            StreamElmChannel(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()).exchange("ATZ", timeoutMs = 50)
        }
    }
}
