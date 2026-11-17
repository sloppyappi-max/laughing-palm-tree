package com.obdscanner.core

import kotlinx.coroutines.delay

/** What the adapter and car told us while connecting. */
data class AdapterInfo(
    val elmVersion: String?,
    val protocol: String?,
    val batteryVoltage: Double?,
    /** False when the adapter answered but the car's ECU did not (often: ignition off). */
    val vehicleResponding: Boolean,
    /** Control modules that answered OBD requests (engine, transmission, …). */
    val ecus: List<Ecu> = emptyList(),
)

/** A control module that answers OBD-II requests, identified by its response header. */
data class Ecu(val header: String) {
    val name: String get() = when (header.uppercase()) {
        "7E8", "18DAF110", "10" -> "Engine (ECM)"
        "7E9", "18DAF118", "18" -> "Transmission (TCM)"
        "7EA", "18DAF128", "28" -> "Hybrid / ABS module"
        else -> "Module $header"
    }
}

/** Mode 09 identification data. */
data class VehicleInfo(
    val vin: String?,
    val calibrationIds: List<String>,
    val cvns: List<String>,
    val ecuNames: List<String>,
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
            ecus = if (responding) runCatching { discoverEcus() }.getOrDefault(emptyList()) else emptyList(),
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
    suspend fun supportedPids(): Set<Int> = supportedBitmask(0x01, last = 0xC0)

    /**
     * Reads "which IDs are supported" bitmasks: request `<mode> 00` lists IDs 01–20, and the
     * last bit says whether `<mode> 20` exists, and so on.
     */
    private suspend fun supportedBitmask(mode: Int, last: Int): Set<Int> {
        val supported = sortedSetOf<Int>()
        var base = 0x00
        while (base <= last) {
            val msgs = (query(mode, base) as? ObdResponse.Data)?.matching(mode, base) ?: break
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

    /**
     * Mode 06: results of the car's own emissions self-tests (catalyst, O2 sensors,
     * EVAP, misfire…). CAN cars only.
     */
    suspend fun readMonitorTests(): List<MonitorTest> {
        val mids = supportedBitmask(0x06, last = 0xE0).filter { it % 0x20 != 0 }
        val tests = mutableListOf<MonitorTest>()
        for (mid in mids) {
            val response = query(0x06, mid) as? ObdResponse.Data ?: continue
            for (m in response.matching(0x06, mid)) tests += MonitorTests.parse(m)
        }
        return tests
    }

    /** Mode 09: VIN, calibration IDs, calibration verification numbers and module names. */
    suspend fun readVehicleInfo(): VehicleInfo {
        suspend fun messages(pid: Int) =
            (query(0x09, pid, timeoutMs = 10_000) as? ObdResponse.Data)?.matching(0x09, pid).orEmpty()

        fun ascii(bytes: List<Byte>) = bytes.map { it.toInt() and 0xFF }
            .filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()

        val calIds = messages(0x04).flatMap { m -> m.drop(3).chunked(16).map(::ascii) }.filter { it.isNotEmpty() }
        val cvns = messages(0x06).flatMap { m ->
            m.drop(3).chunked(4).filter { it.size == 4 }.map { c -> c.joinToString("") { "%02X".format(it) } }
        }
        val names = messages(0x0A).map { m -> ascii(m.drop(3)) }.filter { it.isNotEmpty() }
        return VehicleInfo(readVin(), calIds, cvns, names)
    }

    /**
     * Turns headers on briefly to see which modules answer `0100`. Only safe while nothing
     * else is talking to the adapter, so it runs during [initialize].
     */
    private suspend fun discoverEcus(): List<Ecu> {
        channel.exchange("ATH1")
        try {
            val lines = ElmResponseParser.lines(channel.exchange("0100", timeoutMs = 5_000), "0100")
            return lines.mapNotNull { raw ->
                val line = raw.replace(" ", "").uppercase()
                when (line.indexOf("4100")) {
                    5 -> line.substring(0, 3) // 11-bit CAN: 7E8 06 41 00 …
                    10 -> line.substring(0, 8) // 29-bit CAN: 18DAF110 06 41 00 …
                    6 -> line.substring(4, 6) // older protocols: 48 6B 10 41 00 … (sender is the 3rd byte)
                    else -> null
                }
            }.distinct().sorted().map(::Ecu)
        } finally {
            channel.exchange("ATH0")
        }
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
