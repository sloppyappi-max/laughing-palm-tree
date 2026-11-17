package com.obdscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmResponseParserTest {

    private fun data(raw: String, cmd: String? = null) = ElmResponseParser.parse(raw, cmd) as ObdResponse.Data

    @Test
    fun parsesSingleLineWithSpacesAndPrompt() {
        val r = data("41 0C 1A F8 \r\r>")
        assertEquals(listOf(0x41, 0x0C, 0x1A, 0xF8), r.messages.single().map { it.toInt() and 0xFF })
    }

    @Test
    fun skipsSearchingAndEcho() {
        val r = data("010C\rSEARCHING...\r410C1AF8\r\r", "010C")
        assertEquals(1, r.messages.size)
    }

    @Test
    fun keepsOneMessagePerEcu() {
        val r = data("4100BE3FA813\r410098180001\r\r")
        assertEquals(2, r.matching(0x01, 0x00).size)
    }

    @Test
    fun joinsCanMultiFrame() {
        val raw = "014\r0:490201314847\r1:434D3832363333\r2:41303034333532\r\r"
        val msg = data(raw).messages.single()
        assertEquals(0x14, msg.size)
        assertEquals("1HGCM82633A004352", Vin.parse(listOf(msg)))
    }

    @Test
    fun recognisesNoDataAndErrors() {
        assertEquals(ObdResponse.NoData, ElmResponseParser.parse("NO DATA\r\r"))
        assertEquals(ObdResponse.NoData, ElmResponseParser.parse("7F0112\r\r"))
        assertTrue(ElmResponseParser.parse("SEARCHING...\rUNABLE TO CONNECT\r\r") is ObdResponse.Error)
        assertTrue(ElmResponseParser.parse("?\r\r") is ObdResponse.Error)
        // Progress line followed by data is fine.
        assertTrue(ElmResponseParser.parse("BUS INIT: ...OK\r410D32\r") is ObdResponse.Data)
    }

    @Test
    fun parsesVinFromLegacyProtocolLines() {
        // Non-CAN cars send 4 data bytes per line, numbered by the third byte (may arrive out of order).
        val raw = "49020248474 34D\r49020100000031\r49020338323633\r49020534333532\r49020433413030\r"
        val r = data(raw)
        assertEquals("1HGCM82633A004352", Vin.parse(r.matching(0x09, 0x02)))
    }
}
