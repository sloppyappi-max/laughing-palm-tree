package com.obdscanner.core

/**
 * One Mode 06 on-board monitor test result: the value the car measured during its
 * self-test and the limits it must stay within.
 */
data class MonitorTest(
    val mid: Int,
    val tid: Int,
    val value: Double,
    val min: Double,
    val max: Double,
    val unit: String,
) {
    val monitorName: String get() = MonitorTests.monitorName(mid)
    val testName: String get() = MonitorTests.testName(mid, tid)
    val passed: Boolean get() = value in min..max
}

object MonitorTests {

    private data class Scaling(val unit: String, val factor: Double, val offset: Double = 0.0, val signed: Boolean = false)

    /** SAE J1979 unit and scaling IDs (the common ones; unknown IDs fall back to raw counts). */
    private val scalings: Map<Int, Scaling> = buildMap {
        put(0x01, Scaling("", 1.0)); put(0x02, Scaling("", 0.1)); put(0x03, Scaling("", 0.01))
        put(0x04, Scaling("", 0.001)); put(0x05, Scaling("", 0.0000305)); put(0x06, Scaling("", 0.000305))
        put(0x07, Scaling("rpm", 0.25)); put(0x08, Scaling("km/h", 0.01)); put(0x09, Scaling("km/h", 1.0))
        put(0x0A, Scaling("mV", 0.122)); put(0x0B, Scaling("V", 0.001)); put(0x0C, Scaling("V", 0.01))
        put(0x0D, Scaling("mA", 0.00390625)); put(0x0E, Scaling("A", 0.001)); put(0x0F, Scaling("A", 0.01))
        put(0x10, Scaling("ms", 1.0)); put(0x11, Scaling("ms", 100.0)); put(0x12, Scaling("s", 1.0))
        put(0x13, Scaling("mΩ", 1.0)); put(0x14, Scaling("Ω", 1.0)); put(0x15, Scaling("kΩ", 1.0))
        put(0x16, Scaling("°C", 0.1, -40.0)); put(0x17, Scaling("kPa", 0.01)); put(0x18, Scaling("kPa", 0.0117))
        put(0x19, Scaling("kPa", 0.079)); put(0x1A, Scaling("kPa", 1.0)); put(0x1B, Scaling("kPa", 10.0))
        put(0x1C, Scaling("°", 0.01)); put(0x1D, Scaling("°", 0.5)); put(0x1E, Scaling("λ", 0.0000305))
        put(0x1F, Scaling("A/F", 0.05)); put(0x20, Scaling("ratio", 0.0039062)); put(0x21, Scaling("mHz", 1.0))
        put(0x22, Scaling("Hz", 1.0)); put(0x23, Scaling("kHz", 1.0)); put(0x24, Scaling("counts", 1.0))
        put(0x25, Scaling("km", 1.0)); put(0x26, Scaling("mV/ms", 0.1)); put(0x27, Scaling("g/s", 0.01))
        put(0x28, Scaling("g/s", 1.0)); put(0x29, Scaling("Pa/s", 0.25)); put(0x2A, Scaling("kg/h", 0.001))
        put(0x2B, Scaling("switches", 1.0)); put(0x2C, Scaling("g/cyl", 0.01)); put(0x2D, Scaling("mg/stroke", 0.01))
        put(0x2E, Scaling("", 1.0)); put(0x2F, Scaling("%", 0.01)); put(0x30, Scaling("%", 0.001526))
        put(0x31, Scaling("L", 0.001)); put(0x32, Scaling("in", 0.0000305)); put(0x33, Scaling("ratio", 0.00024414))
        put(0x34, Scaling("min", 1.0)); put(0x35, Scaling("ms", 10.0)); put(0x36, Scaling("g", 0.01))
        put(0x37, Scaling("g", 0.1)); put(0x38, Scaling("g", 1.0)); put(0x39, Scaling("%", 0.01, -327.68))
        put(0x3A, Scaling("g", 0.001)); put(0x3B, Scaling("g", 0.0001)); put(0x3C, Scaling("µs", 0.1))
        put(0x3D, Scaling("mA", 0.01)); put(0x3E, Scaling("mm²", 0.00006103516)); put(0x3F, Scaling("L", 0.01))
        // Signed variants.
        put(0x81, Scaling("", 1.0, signed = true)); put(0x82, Scaling("", 0.1, signed = true))
        put(0x83, Scaling("", 0.01, signed = true)); put(0x84, Scaling("", 0.001, signed = true))
        put(0x85, Scaling("", 0.0000305, signed = true)); put(0x86, Scaling("", 0.000305, signed = true))
        put(0x8A, Scaling("mV", 0.122, signed = true)); put(0x8B, Scaling("V", 0.001, signed = true))
        put(0x8C, Scaling("V", 0.01, signed = true)); put(0x8D, Scaling("mA", 0.00390625, signed = true))
        put(0x8E, Scaling("A", 0.001, signed = true)); put(0x90, Scaling("ms", 1.0, signed = true))
        put(0x96, Scaling("°C", 0.1, signed = true)); put(0x9C, Scaling("°", 0.01, signed = true))
        put(0x9D, Scaling("°", 0.5, signed = true)); put(0xA8, Scaling("g/s", 1.0, signed = true))
        put(0xA9, Scaling("Pa/s", 0.25, signed = true)); put(0xAD, Scaling("mg/stroke", 0.01, signed = true))
        put(0xAF, Scaling("%", 0.01, signed = true)); put(0xB0, Scaling("%", 0.003052, signed = true))
        put(0xB1, Scaling("mV/s", 2.0, signed = true)); put(0xFC, Scaling("kPa", 0.01, signed = true))
        put(0xFD, Scaling("kPa", 0.001, signed = true)); put(0xFE, Scaling("Pa", 0.25, signed = true))
    }

    /** Parses the 9-byte records (MID TID UASID value min max) of an ISO 15765 Mode 06 answer. */
    fun parse(message: ByteArray): List<MonitorTest> {
        val tests = mutableListOf<MonitorTest>()
        var i = 1 // skip 0x46
        while (i + 9 <= message.size) {
            val mid = message.u(i)
            val tid = message.u(i + 1)
            val scaling = scalings[message.u(i + 2)] ?: Scaling("raw", 1.0)
            fun word(at: Int): Double {
                val raw = message.u(at) * 256 + message.u(at + 1)
                val n = if (scaling.signed && raw >= 0x8000) raw - 0x10000 else raw
                return n * scaling.factor + scaling.offset
            }
            tests += MonitorTest(mid, tid, word(i + 3), word(i + 5), word(i + 7), scaling.unit)
            i += 9
        }
        return tests
    }

    private val sensor = listOf("B1S1", "B1S2", "B1S3", "B1S4", "B2S1", "B2S2", "B2S3", "B2S4",
        "B3S1", "B3S2", "B3S3", "B3S4", "B4S1", "B4S2", "B4S3", "B4S4")

    fun monitorName(mid: Int): String = when (mid) {
        in 0x01..0x10 -> "Oxygen sensor ${sensor[mid - 0x01]}"
        in 0x21..0x24 -> "Catalyst bank ${mid - 0x20}"
        in 0x31..0x34 -> "EGR system bank ${mid - 0x30}"
        in 0x35..0x38 -> "VVT system bank ${mid - 0x34}"
        0x39 -> "EVAP (cap off / 0.150\")"
        0x3A -> "EVAP leak (0.090\")"
        0x3B -> "EVAP leak (0.040\")"
        0x3C -> "EVAP leak (0.020\")"
        0x3D -> "EVAP purge flow"
        in 0x41..0x50 -> "Oxygen sensor heater ${sensor[mid - 0x41]}"
        in 0x61..0x64 -> "Heated catalyst bank ${mid - 0x60}"
        in 0x71..0x74 -> "Secondary air system ${mid - 0x70}"
        in 0x81..0x84 -> "Fuel system bank ${mid - 0x80}"
        in 0x85..0x88 -> "Boost pressure bank ${mid - 0x84}"
        in 0x90..0x91 -> "NOx adsorber bank ${mid - 0x8F}"
        in 0x98..0x99 -> "NOx catalyst bank ${mid - 0x97}"
        0xA1 -> "Misfire (general)"
        in 0xA2..0xAD -> "Misfire cylinder ${mid - 0xA1}"
        in 0xB0..0xB1 -> "Particulate filter bank ${mid - 0xAF}"
        else -> "Monitor %02X".format(mid)
    }

    fun testName(mid: Int, tid: Int): String {
        if (mid in 0x01..0x10) when (tid) {
            0x01 -> return "Rich-to-lean threshold voltage"
            0x02 -> return "Lean-to-rich threshold voltage"
            0x03 -> return "Low voltage for switch time"
            0x04 -> return "High voltage for switch time"
            0x05 -> return "Rich-to-lean switch time"
            0x06 -> return "Lean-to-rich switch time"
            0x07 -> return "Minimum voltage"
            0x08 -> return "Maximum voltage"
            0x09 -> return "Time between transitions"
            0x0A -> return "Sensor period"
        }
        if (mid in 0xA1..0xAD) when (tid) {
            0x0B -> return "Misfires (average of last 10 drives)"
            0x0C -> return "Misfires (last/current drive)"
        }
        return if (tid >= 0x80) "Manufacturer test %02X".format(tid) else "Test %02X".format(tid)
    }
}
