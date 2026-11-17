package com.obdscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcAndPidTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun decodesDtcBytes() {
        assertEquals("P0133", Dtc.decode(0x01, 0x33))
        assertEquals("C0300", Dtc.decode(0x43, 0x00))
        assertEquals("B1A2F", Dtc.decode(0x9A, 0x2F))
        assertEquals("U0100", Dtc.decode(0xC1, 0x00))
    }

    @Test
    fun parsesCanDtcsWithCountByte() {
        val codes = Dtc.parse(listOf(bytes(0x43, 0x02, 0x01, 0x33, 0x04, 0x20)), DtcType.STORED)
        assertEquals(listOf("P0133", "P0420"), codes.map { it.code })
    }

    @Test
    fun parsesLegacyDtcsWithPadding() {
        val codes = Dtc.parse(listOf(bytes(0x43, 0x01, 0x33, 0x00, 0x00, 0x00, 0x00)), DtcType.STORED)
        assertEquals(listOf("P0133"), codes.map { it.code })
    }

    @Test
    fun describesKnownAndUnknownCodes() {
        assertEquals("Catalyst system efficiency below threshold (bank 1)", DtcDescriptions.describe("P0420"))
        assertTrue(DtcDescriptions.describe("P0399").contains("ignition"))
        assertTrue(DtcDescriptions.describe("P1234").contains("manufacturer-specific"))
    }

    @Test
    fun decodesPidFormulas() {
        assertEquals(1726.0, Pids.RPM.decode(intArrayOf(0x1A, 0xF8)), 0.01)
        assertEquals(50.0, Pids.COOLANT_TEMP.decode(intArrayOf(90)), 0.01)
        assertEquals(14.2, Pids.MODULE_VOLTAGE.decode(intArrayOf(0x37, 0x78)), 0.001)
        assertEquals(0.0, Pids.STFT_B1.decode(intArrayOf(128)), 0.01)
    }
}
