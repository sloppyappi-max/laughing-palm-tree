package com.obdscanner.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InnovaVciTest {

    private fun hex(s: String) = ElmResponseParser.hexToBytes(s.replace(" ", ""))

    @Test
    fun buildsTheFirmwareVersionFrameFromTheDecompiledApp() {
        // Worked example from the RepairSolutions2 findings: AE 0D 03 00 00 05 05.
        assertArrayEquals(hex("AE 0D 03 00 00 05 05"), InnovaVci.GET_FW_VERSION)
    }

    @Test
    fun checksumCoversLengthAndPayload() {
        val f = InnovaVci.frame(ByteArray(300) { 1 })
        assertEquals(0xAE, f[0].toInt() and 0xFF)
        assertEquals(300 and 0xFF, f[2].toInt() and 0xFF) // 0x2C
        assertEquals(1, f[3].toInt() and 0xFF)
        assertEquals((0x2C + 0x01 + 300) and 0xFF, f[1].toInt() and 0xFF)
    }

    @Test
    fun decodesFramesSplitAcrossNotificationsAndSkipsNoise() {
        val reply = InnovaVci.frame(byteArrayOf(0x00, 0x05, 0x05, 0x31, 0x2E, 0x30))
        val stream = hex("0D 3E") + reply + InnovaVci.frame(byteArrayOf(0x00, 0x01))
        val (frames, consumed) = InnovaVci.decode(stream)
        assertEquals(2, frames.size)
        assertTrue(frames.all { it.checksumOk })
        assertArrayEquals(byteArrayOf(0x00, 0x05, 0x05, 0x31, 0x2E, 0x30), frames[0].payload)
        assertEquals(stream.size, consumed)

        // Half a frame: nothing yet, and nothing consumed from the marker on.
        val half = reply.copyOf(5)
        val (none, used) = InnovaVci.decode(half)
        assertTrue(none.isEmpty())
        assertEquals(0, used)
        assertFalse(InnovaVci.hasCompleteFrame(half))
    }

    @Test
    fun flagsBadChecksums() {
        val bad = InnovaVci.GET_FW_VERSION.copyOf().also { it[1] = 0x00 }
        assertFalse(InnovaVci.decode(bad).first.single().checksumOk)
    }

    @Test
    fun adapterTestSendsOnlyTheFirmwareFrameAndRestoresTextMode() = kotlinx.coroutines.runBlocking {
        val sim = SimulatedElmChannel(latencyMs = 0)
        val client = ObdClient(sim).also { it.initialize() }
        val report = client.adapterReport()
        assertTrue(report, report.contains("> VCI getFwVersion: AE 0D 03 00 00 05 05"))
        assertTrue(report, report.contains("frame: 00 05 05 53 49 4D 20 31 2E 30 |...SIM 1.0|"))
        // The only binary frame sent is the read-only firmware request.
        assertEquals(listOf("BIN AE 0D 03 00 00 05 05"), sim.sent.filter { it.startsWith("BIN") })
        org.junit.Assert.assertNotNull(client.read(Pids.RPM)) // text mode still works afterwards
    }
}
