package com.obdscanner.core

import kotlinx.coroutines.delay

/** What the adapter and car told us while connecting. */
data class AdapterInfo(
    val elmVersion: String?,
    val protocol: String?,
    val batteryVoltage: Double?,
    /** False when the adapter answered but the car's ECU did not (often: ignition off). */
    val vehicleResponding: Boolean,
)

/**
 * High-level OBD-II operations on top of an ELM327 [ElmChannel].
 */
class ObdClient(private val channel: ElmChannel) {

    /**
     * Resets the adapter, sets a compact output format and lets it find the
     * car's protocol automatically.
     */
    suspend fun initialize(): AdapterInfo {
        val reset = channel.exchange("ATZ", timeoutMs = 3_000)
        delay(500) // genuine ELM327s need a moment after reset
        val version = ElmResponseParser.lines(reset, "ATZ").firstOrNull { it.contains("ELM", ignoreCase = true) }

        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) {
            channel.exchange(cmd)
        }

        // The first request triggers the protocol search; it can take ~10 s.
        val probe = ElmResponseParser.parse(channel.exchange("0100", timeoutMs = 15_000), "0100")
        val responding = probe is ObdResponse.Data

        return AdapterInfo(
            elmVersion = version,
            protocol = if (responding) at("ATDP")?.removePrefix("AUTO, ") else null,
            batteryVoltage = batteryVoltage(),
            vehicleResponding = responding,
        )
    }

    /** Sends a raw command and returns the adapter's cleaned-up text lines. */
    suspend fun raw(command: String): List<String> =
        ElmResponseParser.lines(channel.exchange(command, timeoutMs = 10_000), command)

    suspend fun query(mode: Int, pid: Int? = null, timeoutMs: Long = ElmChannel.DEFAULT_TIMEOUT_MS): ObdResponse {
        val command = if (pid == null) "%02X".format(mode) else "%02X%02X".format(mode, pid)
        return ElmResponseParser.parse(channel.exchange(command, timeoutMs), command)
    }

    /** Reads one PID; null when the car does not report it. */
    suspend fun read(pid: Pid): Double? {
        val data = (query(0x01, pid.code) as? ObdResponse.Data)?.matching(0x01, pid.code)?.firstOrNull() ?: return null
        val payload = IntArray(data.size - 2) { data.u(it + 2) }
        return runCatching { pid.decode(payload) }.getOrNull()
    }

    /** The Mode 01 PIDs this car supports, from the 0100/0120/0140/… bitmasks. */
    suspend fun supportedPids(): Set<Int> {
        val supported = sortedSetOf<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val msgs = (query(0x01, base) as? ObdResponse.Data)?.matching(0x01, base) ?: break
            var mask = 0L
            for (m in msgs) {
                if (m.size < 6) continue
                mask = mask or ((m.u(2).toLong() shl 24) or (m.u(3).toLong() shl 16) or (m.u(4).toLong() shl 8) or m.u(5).toLong())
            }
            for (bit in 0 until 32) {
                if (mask and (1L shl (31 - bit)) != 0L) supported += base + bit + 1
            }
            if (!supported.contains(base + 0x20)) break
            base += 0x20
        }
        return supported
    }

    suspend fun monitorStatus(): MonitorStatus? {
        val msgs = (query(0x01, 0x01) as? ObdResponse.Data)?.matching(0x01, 0x01)
            ?.filter { it.size >= 6 } ?: return null
        if (msgs.isEmpty()) return null
        // Several ECUs may answer: the MIL is on if any says so and codes add up, but readiness
        // comes from the engine computer, which reports the most monitors.
        val decoded = msgs.map { MonitorStatus.decode(it.u(2), it.u(3), it.u(4), it.u(5)) }
        return decoded.maxBy { it.monitors.size }.copy(
            milOn = decoded.any { it.milOn },
            dtcCount = decoded.sumOf { it.dtcCount },
        )
    }

    /**
     * Mode 02: the snapshot the engine computer saved when the first code was set.
     * Null when no freeze frame is stored.
     */
    suspend fun readFreezeFrame(pids: List<Pid> = FreezeFrame.DEFAULT_PIDS): FreezeFrame? {
        val trigger = freezeFrameData(0x02)?.takeIf { it.size >= 2 && (it[0] != 0 || it[1] != 0) } ?: return null
        val values = LinkedHashMap<Pid, Double>()
        for (pid in pids) {
            val data = freezeFrameData(pid.code) ?: continue
            runCatching { pid.decode(data) }.getOrNull()?.let { values[pid] = it }
        }
        return FreezeFrame(Dtc.decode(trigger[0], trigger[1]), values)
    }

    /** Data bytes of `02 <pid> 00` (frame 0), or null. */
    private suspend fun freezeFrameData(pid: Int): IntArray? {
        val command = "02%02X00".format(pid)
        val response = ElmResponseParser.parse(channel.exchange(command), command) as? ObdResponse.Data ?: return null
        val m = response.matching(0x02, pid).firstOrNull() ?: return null
        // 42 <pid> <frame> data...  (some ECUs omit the frame byte)
        val start = if (m.size >= 4) 3 else 2
        return IntArray(m.size - start) { m.u(it + start) }
    }

    suspend fun readDtcs(type: DtcType): List<Dtc> {
        val response = query(type.mode) as? ObdResponse.Data ?: return emptyList()
        return Dtc.parse(response.matching(type.mode), type)
    }

    /** Mode 04: clears stored codes and turns the check-engine light off. */
    suspend fun clearDtcs(): Boolean {
        val response = query(0x04, timeoutMs = 10_000)
        return response is ObdResponse.Data && response.matching(0x04).isNotEmpty()
    }

    /** Mode 09 PID 02: the 17-character VIN, or null if the car does not report it. */
    suspend fun readVin(): String? {
        val response = query(0x09, 0x02, timeoutMs = 10_000) as? ObdResponse.Data ?: return null
        return Vin.parse(response.matching(0x09, 0x02))
    }

    /** Voltage at the OBD port, measured by the adapter itself (`ATRV`). */
    suspend fun batteryVoltage(): Double? =
        at("ATRV")?.uppercase()?.removeSuffix("V")?.trim()?.toDoubleOrNull()

    private suspend fun at(command: String): String? =
        ElmResponseParser.lines(channel.exchange(command), command).firstOrNull()
            ?.takeUnless { it == "?" }
}

/** The conditions recorded when [dtc] was set. */
data class FreezeFrame(val dtc: String, val values: Map<Pid, Double>) {
    companion object {
        val DEFAULT_PIDS = listOf(
            Pids.RPM, Pids.SPEED, Pids.COOLANT_TEMP, Pids.ENGINE_LOAD, Pids.THROTTLE, Pids.INTAKE_TEMP,
            Pids.INTAKE_MAP, Pids.MAF, Pids.STFT_B1, Pids.LTFT_B1, Pids.STFT_B2, Pids.LTFT_B2,
        )
    }
}

object Vin {
    /** Joins Mode 09/02 messages from CAN (one multi-frame message) or older protocols (one line per chunk). */
    fun parse(messages: List<ByteArray>): String? {
        if (messages.isEmpty()) return null
        val bytes = if (messages.size == 1) {
            messages[0].drop(3)
        } else {
            messages.sortedBy { it.u(2) }.flatMap { it.drop(3) }
        }
        val text = bytes
            .map { it.toInt() and 0xFF }
            .filter { it in 0x21..0x7E }
            .map { it.toChar() }
            .joinToString("")
        return text.takeLast(17).takeIf { it.length == 17 }
    }
}
