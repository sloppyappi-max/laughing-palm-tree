package com.obdscanner.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** What Innova's CodeReader dongles print in their ELM327 mode (from a real adapter test log). */
class InnovaElmModeTest {

    private val vin = "1HGCM82633A004352"
    private fun asciiHex(s: String) = s.map { "%02X".format(it.code) }.joinToString("")

    @Test
    fun parsesFirstFrameThenSequenceLines() {
        // 20 bytes: 49 02 01 + 17 VIN characters, as 6 + 7 + 7 with the "21"/"22" bytes left in.
        val hex = "490201" + asciiHex(vin)
        val raw = hex.substring(0, 12) + "\r\r>" + "21" + hex.substring(12, 26) + "\r22" + hex.substring(26) + "\r\r"
        val response = ElmResponseParser.parse(raw, "0902") as ObdResponse.Data
        assertEquals(vin, Vin.parse(response.matching(0x09, 0x02)))
    }

    @Test
    fun parsesMultiFrameWithHeadersOn() {
        val hex = "490201" + asciiHex(vin)
        val raw = "7E81014" + hex.substring(0, 12) + "\r7E821" + hex.substring(12, 26) + "\r7E822" + hex.substring(26) + "\r"
        val response = ElmResponseParser.parse(raw, "0902") as ObdResponse.Data
        assertEquals(vin, Vin.parse(response.matching(0x09, 0x02)))
    }

    @Test
    fun separateModuleAnswersStaySeparate() {
        val response = ElmResponseParser.parse("4100BFFFB993\r\r\r410080400001\r\r\r410080000001\r\r", "0100") as ObdResponse.Data
        assertEquals(3, response.matching(0x01, 0x00).size)
    }

    @Test
    fun wholeAppWorksWithInnovaQuirks() = runBlocking {
        val client = ObdClient(SimulatedElmChannel(latencyMs = 0, innovaQuirks = true))
        val info = client.initialize()
        assertTrue(info.vehicleResponding)
        assertEquals("ISO 15765-4 (CAN 11-bit)", info.protocol) // ATDP only said "OK"
        assertTrue(client.supportsModuleAccess)
        assertTrue(client.isCan)
        val vehicle = client.readVehicleInfo()
        assertEquals(vin, vehicle.vin)
        assertEquals(listOf("12653917"), vehicle.calibrationIds)
        // Readiness comes from the engine computer, not the module that answered first.
        assertTrue(client.monitorStatus()!!.monitors.size > 1)
        assertTrue(client.readMonitorTests().isNotEmpty()) // Mode 06 now runs (CAN detected)
    }

    @Test
    fun streamChannelKeepsEveryModulesAnswer() = runBlocking {
        var answer = ByteArrayInputStream(ByteArray(0))
        val input = object : InputStream() {
            override fun read() = answer.read()
            override fun available() = answer.available()
        }
        val out = object : ByteArrayOutputStream() {
            override fun flush() { answer = ByteArrayInputStream("4100BFFFB993\r\r>410080400001\r\r>".toByteArray()) }
        }
        val channel = StreamElmChannel(input, out)
        val response = ElmResponseParser.parse(channel.exchange("0100"), "0100") as ObdResponse.Data
        assertEquals(2, response.messages.size)
        assertEquals(0, answer.available()) // nothing left over for the next command
    }
}
