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
    /** What ATDP reports. Anything without "CAN 11/" simulates a car without module access. */
    private val protocol: String = "ISO 15765-4 (CAN 11/500)",
    /** False simulates an adapter whose automatic protocol search never locks on (needs ATSP6). */
    private val autoSearchWorks: Boolean = true,
    /** True simulates an adapter that ignores ATH0 and always prints CAN headers. */
    private val alwaysHeaders: Boolean = false,
    /**
     * True simulates a car where the OBD broadcast (7DF) only gets a refusal (7F 01 11) from some
     * module and the engine computer answers only when addressed directly at 7E0.
     */
    private val broadcastRefused: Boolean = false,
) : ElmChannel {

    private var protocolNumber = "0"

    /** Every command received, for tests. */
    val sent: MutableList<String> = mutableListOf()
    private val can11 get() = protocol.contains("CAN 11/")

    private var stored = storedCodes
    /** Clearing codes resets readiness monitors, like a real car. */
    private var cleared = false
    private var headers = false
    /** Request ID set with ATSH; 7DF is the normal OBD-II broadcast. */
    private var target = "7DF"
    private var odometerKm = 84_213.0
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
            0x01 -> intArrayOf(if (stored.isEmpty()) 0x00 else 0x80 or stored.size, 0x07, 0xE5, if (cleared) 0x25 else 0x00)
            0x04 -> one((15 + wave * 55) * 255 / 100)
            0x03 -> intArrayOf(if (t < 8) 1 else 2, 0)
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
            0x13 -> one(0x33.toDouble())
            0x14 -> intArrayOf(((0.45 + sin(t * 9) * 0.4) * 200).roundToInt().coerceIn(0, 255), 0xFF)
            0x15 -> intArrayOf(((0.7 + sin(t * 0.7) * 0.05) * 200).roundToInt(), 0xFF)
            0x1C -> one(1.0)
            0x1F -> two(t)
            0x21 -> two(if (stored.isEmpty()) 0.0 else 132.0)
            0x2F -> one(62.0 * 255 / 100)
            0x31 -> two(4210.0)
            0x33 -> one(101.0)
            0x42 -> two((14.1 + sin(t) * 0.1) * 1000)
            0x3C -> two((minOf(650.0, 150 + t * 20) + wave * 100 + 40) * 10)
            0x44 -> two(1.0 * 65536 / 2)
            0x46 -> one(21.0 + 40)
            0x49 -> one((10 + wave * 45) * 255 / 100)
            0x51 -> one(1.0)
            0xA6 -> (odometerKm * 10).toLong().let {
                intArrayOf((it shr 24).toInt() and 0xFF, (it shr 16).toInt() and 0xFF, (it shr 8).toInt() and 0xFF, it.toInt() and 0xFF)
            }
            0x5C -> one(minOf(98.0, 35 + t * 1.5) + 40)
            else -> null
        }
    }

    private val supported = (0x01..0xC8).filter { it % 0x20 != 0 && pidBytes(it) != null }.toSet()

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

    private fun ascii(text: String, length: Int) = IntArray(length) { i -> text.getOrNull(i)?.code ?: 0 }

    /** Mode 06 results: O2 sensor and catalyst pass, the 0.020" EVAP leak test fails. */
    private fun monitorTests(mid: Int?): String {
        fun record(mid: Int, tid: Int, uasid: Int, value: Int, min: Int, max: Int) =
            intArrayOf(mid, tid, uasid, value shr 8, value and 0xFF, min shr 8, min and 0xFF, max shr 8, max and 0xFF)
        val records = when (mid) {
            0x00 -> return hex(0x46, 0x00, 0x80, 0x00, 0x00, 0x01) // MID 01, and more from 0x20
            0x20 -> return hex(0x46, 0x20, 0x80, 0x00, 0x00, 0x10) // MIDs 21 and 3C
            0x01 -> record(0x01, 0x01, 0x0A, 3690, 2459, 4918) + record(0x01, 0x05, 0x10, 80, 0, 120)
            0x21 -> record(0x21, 0x80, 0x24, 33, 0, 70)
            0x3C -> record(0x3C, 0x80, 0x01, 410, 0, 380)
            else -> return "NO DATA"
        }
        return canMessage(intArrayOf(0x46) + records)
    }

    /**
     * Fake modules for the all-system scan: engine and transmission answer UDS, an ABS module
     * at GM-style address 241 has a code, and an airbag module at 247 only speaks GMLAN $A9.
     */
    private fun moduleResponse(module: String, cmd: String): String {
        fun udsCodes(vararg codes: Pair<String, Int>): String {
            val bytes = intArrayOf(0x59, 0x02, 0xFF) + codes.flatMap { (code, failure) ->
                encodeDtc(code).toList() + listOf(failure, 0x2F)
            }
            return canMessage(bytes)
        }
        return when (module) {
            "7E0" -> when (cmd) {
                "3E00" -> "7E00"
                "1902FF" -> udsCodes(*stored.map { it to 0x00 }.toTypedArray())
                else -> "7F" + cmd.take(2) + "31"
            }
            "7E2" -> when (cmd) {
                "3E00" -> "7E00"
                "1902FF" -> udsCodes()
                "221940" -> hex(0x62, 0x19, 0x40, (minOf(80.0, 30 + t() * 2) + 40).roundToInt())
                "221941" -> hex(0x62, 0x19, 0x41, 0x03, 0x20)
                else -> "7F" + cmd.take(2) + "31"
            }
            "241" -> when (cmd) {
                "3E00" -> "7E00"
                "1902FF" -> udsCodes("C0035" to 0x5A)
                else -> "7F" + cmd.take(2) + "31"
            }
            "247" -> when (cmd) {
                "3E00" -> "7E00"
                "1902FF" -> "7F1911"
                // GMLAN: raw frames from the module's UUDT ID, headers on, no ISO-TP length byte.
                "03A98112" -> "547" + hex(0x81, *encodeDtc("B0001"), 0x00, 0x2F, 0, 0, 0) +
                    "\r547" + hex(0x81, 0, 0, 0, 0, 0, 0, 0)
                else -> "7F" + cmd.take(2) + "11"
            }
            else -> "NO DATA"
        }
    }

    override suspend fun exchange(command: String, timeoutMs: Long): String {
        delay(latencyMs)
        val cmd = command.uppercase().replace(" ", "")
        sent += cmd
        if (cmd.startsWith("ATSP")) protocolNumber = cmd.removePrefix("ATSP")
        if (!autoSearchWorks && protocolNumber == "0" && !cmd.startsWith("AT")) return "SEARCHING...\rUNABLE TO CONNECT\r\r"
        if (!autoSearchWorks && protocolNumber != "0" && protocolNumber != "6" && !cmd.startsWith("AT")) return "NO DATA\r\r"
        // On an older car, a 3-digit ATSH header makes every request go nowhere.
        if (!can11 && target != "7DF" && !cmd.startsWith("AT")) return "NO DATA\r\r"
        val body = when {
            cmd == "ATZ" -> "\r\rELM327 v1.5"
            cmd == "ATRV" -> "12.6V"
            cmd == "ATDP" -> "AUTO, $protocol"
            cmd == "ATDPN" -> if (can11) "A6" else "A3"
            cmd == "ATI" -> "ELM327 v1.5"
            cmd == "ATH1" -> { headers = true; "OK" }
            cmd == "ATH0" -> { headers = false; "OK" }
            cmd.startsWith("ATSH") -> { target = cmd.removePrefix("ATSH"); "OK" }
            broadcastRefused && target == "7DF" && cmd.startsWith("01") -> if (headers) "7E9037F0111" else "7F0111"
            // At 7E0 the engine answers normal OBD requests too; module-only services go to moduleResponse.
            !cmd.startsWith("AT") && target != "7DF" && (target != "7E0" || cmd.take(2) in setOf("3E", "19", "22")) ->
                moduleResponse(target, cmd)
            headers && cmd == "0100" && can11 && target == "7DF" -> "7E806" + hex(0x41, 0x00, *supportMask(0x00)) + "\r7E906" + hex(0x41, 0x00, 0x80, 0, 0, 0)
            headers && cmd == "0100" -> "486B10" + hex(0x41, 0x00, *supportMask(0x00)) + "00"
            // With headers on, normal answers carry the sender's ID, which breaks header-less parsing.
            (headers || alwaysHeaders) && cmd.length == 4 && cmd.startsWith("01") -> {
                val pid = cmd.substring(2).toInt(16)
                val bytes = if (pid % 0x20 == 0) intArrayOf(0x41, pid, *supportMask(pid)) else pidBytes(pid)?.let { intArrayOf(0x41, pid, *it) }
                bytes?.let { "7E8%02X".format(it.size) + hex(*it) } ?: "NO DATA"
            }
            cmd.startsWith("AT") -> "OK"
            cmd == "03" -> dtcResponse(0x03, stored)
            cmd == "07" -> dtcResponse(0x07, pendingCodes)
            cmd == "0A" -> dtcResponse(0x0A, emptyList())
            cmd == "04" -> { stored = emptyList(); cleared = true; "44" }
            cmd.length == 6 && cmd.startsWith("02") && cmd.endsWith("00") -> {
                val pid = cmd.substring(2, 4).toIntOrNull(16)
                when {
                    pid == null -> "?"
                    pid == 0x02 -> stored.firstOrNull()?.let { hex(0x42, 0x02, 0x00, *encodeDtc(it)) } ?: "4202000000"
                    stored.isEmpty() -> "NO DATA"
                    else -> pidBytes(pid)?.let { hex(0x42, pid, 0x00, *it) } ?: "NO DATA"
                }
            }
            cmd == "0902" -> canMessage(intArrayOf(0x49, 0x02, 0x01) + vin.map { it.code }.toIntArray())
            cmd == "0904" -> canMessage(intArrayOf(0x49, 0x04, 0x01) + ascii("12653917", 16))
            cmd == "0906" -> canMessage(intArrayOf(0x49, 0x06, 0x01, 0x1A, 0x2B, 0x3C, 0x4D))
            cmd == "090A" -> canMessage(intArrayOf(0x49, 0x0A, 0x01) + ascii("ECM-EngineControl", 20))
            cmd.length == 4 && cmd.startsWith("06") -> monitorTests(cmd.substring(2).toIntOrNull(16))
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

    /** Simulated Innova VCI: answers the firmware-version frame with a framed reply. */
    override suspend fun exchangeBytes(data: ByteArray, timeoutMs: Long, complete: (ByteArray) -> Boolean): ByteArray {
        delay(latencyMs)
        sent += "BIN " + data.joinToString(" ") { "%02X".format(it) }
        val frame = InnovaVci.decode(data).first.firstOrNull() ?: return ByteArray(0)
        return if (frame.payload.contentEquals(byteArrayOf(0x00, 0x05, 0x05))) {
            // Reply format: EA b1 lenLo lenHi | 4 header bytes | body
            val payload = byteArrayOf(0x00, 0x05, 0x00, 0x00) + "SIM 1.0".toByteArray()
            byteArrayOf(0xEA.toByte(), 0, payload.size.toByte(), 0) + payload
        } else {
            ByteArray(0)
        }
    }

    override fun close() = Unit
}
