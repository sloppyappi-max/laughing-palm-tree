package com.obdscanner.core

import com.obdscanner.core.InnovaVci.hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte vectors from the decompiled RepairSolutions2 app (com.innova.rs2 2.7.5). */
class InnovaVciTest {

    private fun h(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test fun requestFrames() {
        assertEquals("AE 03 02 00 00 01", InnovaVci.AUTOLINK.hex())
        assertEquals("AE 0D 03 00 00 05 05", InnovaVci.GET_FW_VERSION.hex())
        assertEquals("AE 0C 03 00 00 05 04", InnovaVci.GET_GUID_PRODUCT_ID.hex())
        assertEquals("AE 09 03 00 00 05 01", InnovaVci.GET_STATUS.hex())
    }

    @Test fun vinWriteFrame() {
        val f = InnovaVci.write(InnovaVci.PROTO_CAN, 0x7DF, h("09 02"))
        assertEquals("AE 6B 15 00 00 02 0F 00 03 0E 00 1D 00 20 00 00 00 06 00 00 00 07 DF 09 02", f.hex())
    }

    @Test fun guardBlocksDongleWrites() {
        assertFalse(InnovaVci.isSafe(h("AE 0F 03 00 00 07 05"))) // reboot (UPGRADE 05)
        assertFalse(InnovaVci.isSafe(h("AE 0B 03 00 00 07 01"))) // reboot to bootloader (UPGRADE 01)
        assertFalse(InnovaVci.isSafe(h("AE 0A 03 00 00 05 02"))) // batteryClearStatus
        assertFalse(InnovaVci.isSafe(InnovaVci.command(InnovaVci.CMD_WRITE_DB)))
        assertFalse(InnovaVci.isSafe(InnovaVci.command(InnovaVci.CMD_WRITE_ID)))
        assertTrue(InnovaVci.isSafe(InnovaVci.AUTOLINK))
        assertTrue(InnovaVci.isSafe(InnovaVci.GET_FW_VERSION))
        assertTrue(InnovaVci.isSafe(InnovaVci.write(InnovaVci.PROTO_CAN, 0x7DF, h("01 00"))))
    }

    @Test fun decodesVinReplyIncludingSplitNotifications() {
        val rx = h(
            "EA AC 29 00 00 02 00 00 23 00 03 22 00 1D 00 00 00 00 00 18 00 00 00 07 E8 " +
                "49 02 01 31 47 31 4A 43 35 34 34 34 52 37 32 35 32 33 36 37",
        )
        assertTrue(InnovaVci.hasCompleteReply(rx))
        assertFalse(InnovaVci.hasCompleteReply(rx.copyOfRange(0, 20)))
        val msgs = InnovaVci.parseWrite(InnovaVci.replies(rx).single().body)
        assertEquals(1, msgs.size)
        assertEquals(0x7E8, msgs[0].id)
        assertEquals("1G1JC5444R7252367", String(msgs[0].data.copyOfRange(3, msgs[0].data.size), Charsets.US_ASCII))
    }

    @Test fun decodesAutoLinkBody() {
        assertEquals(0x1D, InnovaVci.parseAutoLink(h("00 00 02 00 1D 00")))
        assertEquals(0, InnovaVci.parseAutoLink(h("00 00 03 00 1D 00")))
    }

    @Test fun adapterTestSendsOnlyTheFirmwareFrame() = runBlocking {
        val sim = SimulatedElmChannel(latencyMs = 0)
        val client = ObdClient(sim).also { it.initialize() }
        val report = client.adapterReport()
        assertTrue(report, report.contains("> VCI getFwVersion: AE 0D 03 00 00 05 05"))
        assertTrue(report, report.contains("native reply frame: 00 05 00 00 53 49 4D 20 31 2E 30 |....SIM 1.0|"))
        assertEquals(listOf("BIN AE 0D 03 00 00 05 05"), sim.sent.filter { it.startsWith("BIN") })
        assertNotNull(client.read(Pids.RPM)) // text mode still works afterwards
    }
}
