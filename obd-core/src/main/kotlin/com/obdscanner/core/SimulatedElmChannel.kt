package com.obdscanner.core

import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A fake ELM327 on a CAN car, for demo mode and tests. Responds the way a real
 * adapter does after `ATE0 ATS0 ATH0` (no echo, no spaces, no headers).
 */
class SimulatedElmChannel(
    private val latencyMs: Long = 25,
    storedCodes: List<String> = listOf("P0133", "P0420"),
    private val pendingCodes: List<String> = listOf("P0301"),
    private val vin: String = "1HGCM82633A004352",
) : ElmChannel {

    private var stored = storedCodes
    private val startedAt = System.currentTimeMillis()

    private fun t() = (System.currentTimeMillis() - startedAt) / 1000.0

    /** Current raw data bytes for each supported PID. */
    private fun pidBytes(pid: Int): IntArray? {
        val t = t()
        val wave = (sin(t * 2 * PI / 12) + 1) / 2 // slow 0..1 cycle: idle → accelerate → idle
        val rpm = 750 + wave * 2800 + sin(t * 7) * 40
        val speed = wave * 95
        val coolant = minOf(90.0, 40 + t * 2)
        fun one(v: Double) = intArrayOf(v.roundToInt().coerceIn(0, 255))
        fun two(v: Double) = v.roundToInt().coerceIn(0, 65535).let { intArrayOf(it shr 8, it and 0xFF) }
        return when (pid) {
            0x01 -> intArrayOf(if (stored.isEmpty()) 0x00 else 0x80 or stored.size, 0x07, 0xE5, 0x00)
            0x04 -> one((15 + wave * 55) * 255 / 100)
            0x05 -> one(coolant + 40)
            0x06 -> one((100 + sin(t * 3) * 4) * 1.28)
            0x07 -> one(102.5 * 1.28)
            0x0B -> one(30 + wave * 60)
            0x0C -> two(rpm * 4)
            0x0D -> one(speed)
            0x0E -> one((10 + wave * 15 + 64) * 2)
            0x0F -> one(28.0 + 40)
            0x10 -> two((3 + wave * 30) * 100)
            0x11 -> one((14 + wave * 50) * 255 / 100)
            0x1F -> two(t)
            0x21 -> two(if (stored.isEmpty()) 0.0 else 132.0)
            0x2F -> one(62.0 * 255 / 100)
            0x31 -> two(4210.0)
            0x33 -> one(101.0)
            0x42 -> two((14.1 + sin(t) * 0.1) * 1000)
            0x46 -> one(21.0 + 40)
            0x5C -> one(minOf(98.0, 35 + t * 1.5) + 40)
            else -> null
        }
    }

    private val supported = (0x01..0x60).filter { pidBytes(it) != null }.toSet()

    private fun supportMask(base: Int): IntArray {
        var mask = 0L
        for (bit in 0 until 32) {
            val pid = base + bit + 1
            val isNextRange = bit == 31 && supported.any { it > pid }
            if (pid in supported || isNextRange) mask = mask or (1L shl (31 - bit))
        }
        return intArrayOf((mask shr 24).toInt() and 0xFF, (mask shr 16).toInt() and 0xFF, (mask shr 8).toInt() and 0xFF, mask.toInt() and 0xFF)
    }

    private fun hex(vararg bytes: Int) = bytes.joinToString("") { "%02X".format(it) }

    private fun encodeDtc(code: String): IntArray {
        val system = "PCBU".indexOf(code[0])
        val a = (system shl 6) or (code[1].digitToInt() shl 4) or code[2].digitToInt(16)
        return intArrayOf(a, code.substring(3).toInt(16))
    }

    private fun dtcResponse(mode: Int, codes: List<String>): String {
        val payload = intArrayOf(mode + 0x40, codes.size) + codes.flatMap { encodeDtc(it).toList() }
        return canMessage(payload)
    }

    /** Formats a CAN message as an ELM327 prints it with headers off (multi-frame if > 7 bytes). */
    private fun canMessage(payload: IntArray): String {
        if (payload.size <= 7) return hex(*payload)
        val lines = mutableListOf("%03X".format(payload.size))
        lines += "0:" + hex(*payload.take(6).toIntArray())
        payload.drop(6).chunked(7).forEachIndexed { i, chunk ->
            lines += "%X:".format((i + 1) % 16) + hex(*chunk.toIntArray()) + "00".repeat(7 - chunk.size)
        }
        return lines.joinToString("\r")
    }

    override suspend fun exchange(command: String, timeoutMs: Long): String {
        delay(latencyMs)
        val cmd = command.uppercase().replace(" ", "")
        val body = when {
            cmd == "ATZ" -> "\r\rELM327 v1.5"
            cmd == "ATRV" -> "12.6V"
            cmd == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
            cmd == "ATDPN" -> "A6"
            cmd == "ATI" -> "ELM327 v1.5"
            cmd.startsWith("AT") -> "OK"
            cmd == "03" -> dtcResponse(0x03, stored)
            cmd == "07" -> dtcResponse(0x07, pendingCodes)
            cmd == "0A" -> dtcResponse(0x0A, emptyList())
            cmd == "04" -> { stored = emptyList(); "44" }
            cmd == "0902" -> canMessage(intArrayOf(0x49, 0x02, 0x01) + vin.map { it.code }.toIntArray())
            cmd.length == 4 && cmd.startsWith("01") -> {
                val pid = cmd.substring(2).toIntOrNull(16)
                when {
                    pid == null -> "?"
                    pid % 0x20 == 0 -> hex(0x41, pid, *supportMask(pid))
                    else -> pidBytes(pid)?.let { hex(0x41, pid, *it) } ?: "NO DATA"
                }
            }
            else -> "NO DATA"
        }
        return "$body\r\r"
    }

    override fun close() = Unit
}
