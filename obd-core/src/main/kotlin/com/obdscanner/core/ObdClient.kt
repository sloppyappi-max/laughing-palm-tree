package com.obdscanner.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
     * Serialises whole operations, not just single commands: manufacturer requests change the
     * adapter's header and filters, and nothing else may run until they are restored.
     */
    private val lock = Mutex()

    /**
     * What a manufacturer request changed and the next normal request must undo. Tracked separately so
     * only those settings are restored (and nothing CAN-specific is ever sent to an older car).
     */
    private var headerChanged = false
    private var timeoutChanged = false

    /** Header currently set by a custom read, so consecutive reads to the same module skip ATSH. */
    private var currentHeader: String? = null

    /** Only 11-bit CAN cars (2008+ in the US) support module addressing in this app. */
    var supportsModuleAccess = false
        private set

    /** ISO 15765 (CAN). Mode 06 results are only decoded in the CAN format. */
    var isCan = false
        private set

    private suspend fun sx(command: String, timeoutMs: Long = ElmChannel.DEFAULT_TIMEOUT_MS) =
        channel.exchange(command, timeoutMs)

    /** Puts the adapter back into the normal OBD-II setup after a manufacturer request. */
    private suspend fun restoreDefaults() {
        if (timeoutChanged) {
            runCatching { sx("ATST32") } // ELM327 default response timeout (~200 ms)
            timeoutChanged = false
        }
        if (headerChanged) {
            // Only ever set on 11-bit CAN cars, where 7DF is the OBD-II broadcast address and
            // 7E8–7EF are the answers. CF/CM are supported by every ELM327 version, unlike CRA/AR.
            for (cmd in listOf("ATSH7DF", "ATFCSM0", "ATAR", "ATCF7E8", "ATCM7F8")) runCatching { sx(cmd) }
            headerChanged = false
            currentHeader = null
        }
    }

    /** One command in the normal OBD-II setup. */
    private suspend fun send(command: String, timeoutMs: Long = ElmChannel.DEFAULT_TIMEOUT_MS): String =
        lock.withLock {
            restoreDefaults()
            sx(command, timeoutMs)
        }

    /**
     * Runs [block] with exclusive use of the adapter. Header / timeout changes it makes are flagged and
     * restored by the next normal request; header and CAN-format changes are undone in place.
     */
    private suspend fun <T> session(block: suspend () -> T): T = lock.withLock { block() }

    /**
     * Resets the adapter, sets a compact output format and lets it find the
     * car's protocol automatically.
     */
    suspend fun initialize(): AdapterInfo {
        val reset = send("ATZ", timeoutMs = 3_000)
        delay(500) // genuine ELM327s need a moment after reset
        val version = ElmResponseParser.lines(reset, "ATZ").firstOrNull { it.contains("ELM", ignoreCase = true) }

        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) {
            send(cmd)
        }

        // The first request triggers the protocol search; it can take ~10 s.
        val probe = ElmResponseParser.parse(send("0100", timeoutMs = 15_000), "0100")
        val responding = probe is ObdResponse.Data
        val protocol = if (responding) at("ATDP")?.removePrefix("AUTO, ") else null
        supportsModuleAccess = protocol?.contains("11/") == true
        isCan = protocol?.contains("15765") == true || protocol?.contains("CAN") == true

        return AdapterInfo(
            ecus = if (responding) runCatching { discoverEcus() }.getOrDefault(emptyList()) else emptyList(),
            elmVersion = version,
            protocol = protocol,
            batteryVoltage = batteryVoltage(),
            vehicleResponding = responding,
        )
    }

    /**
     * Sends a raw command (terminal) and returns the adapter's cleaned-up text lines. Settings commands
     * that would change the output format the app relies on (reset, echo, spaces, headers…) are
     * followed by the app's own setup so live data keeps working.
     */
    suspend fun raw(command: String): List<String> {
        val cmd = command.replace(" ", "").uppercase()
        val lines = ElmResponseParser.lines(send(cmd, timeoutMs = 10_000), cmd)
        val readOnly = setOf("ATI", "AT@1", "ATDP", "ATDPN", "ATRV", "ATRD", "ATCS", "ATPPS")
        if (cmd.startsWith("AT") && cmd !in readOnly) {
            lock.withLock {
                if (cmd.startsWith("ATZ") || cmd.startsWith("ATD") || cmd.startsWith("ATWS")) delay(500)
                for (setup in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATCAF1", "ATAT1")) runCatching { sx(setup) }
                if (cmd.startsWith("ATSH") || cmd.startsWith("ATCRA") || cmd.startsWith("ATCF") || cmd.startsWith("ATCM")) {
                    headerChanged = supportsModuleAccess
                }
            }
        }
        return lines
    }

    suspend fun query(mode: Int, pid: Int? = null, timeoutMs: Long = ElmChannel.DEFAULT_TIMEOUT_MS): ObdResponse {
        val command = if (pid == null) "%02X".format(mode) else "%02X%02X".format(mode, pid)
        return ElmResponseParser.parse(send(command, timeoutMs), command)
    }

    /** Reads one PID; null when the car does not report it. */
    suspend fun read(pid: Pid): Double? {
        pid.request?.let { return readCustom(pid, it) }
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
        if (!isCan) return emptyList()
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
    private suspend fun discoverEcus(): List<Ecu> = lock.withLock {
        sx("ATH1")
        val lines = try {
            ElmResponseParser.lines(sx("0100", timeoutMs = 5_000), "0100")
        } finally {
            runCatching { sx("ATH0") }
        }
        lines.mapNotNull { raw ->
            val line = raw.replace(" ", "").uppercase()
            when (line.indexOf("4100")) {
                5 -> line.substring(0, 3) // 11-bit CAN: 7E8 06 41 00 …
                10 -> line.substring(0, 8) // 29-bit CAN: 18DAF110 06 41 00 …
                6 -> line.substring(4, 6) // older protocols: 48 6B 10 41 00 … (sender is the 3rd byte)
                else -> null
            }
        }.distinct().sorted().map(::Ecu)
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
        val response = ElmResponseParser.parse(send(command), command) as? ObdResponse.Data ?: return null
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

    // ---------------------------------------------------------------------------------------
    // Manufacturer-specific access (11-bit CAN). Read-only services only:
    // 0x3E TesterPresent, 0x22 ReadDataByIdentifier, 0x19 ReadDTCInformation, 0xA9 (GMLAN read DTCs).
    // ---------------------------------------------------------------------------------------

    /** Response ID for a request ID: +8 for the 7xx range, +0x400 for GM-style 2xx addresses. */
    private fun responseIdFor(requestId: Int): Int = if (requestId < 0x300) requestId + 0x400 else requestId + 8

    /** Points the adapter at one module: header, receive filter and ISO-TP flow control. */
    private suspend fun target(requestId: Int) {
        val hex = "%03X".format(requestId)
        if (currentHeader == hex) return
        headerChanged = true
        sx("ATSH$hex")
        // Receive filter: exactly this module's answer ID.
        sx("ATCF%03X".format(responseIdFor(requestId)))
        sx("ATCM7FF")
        // Flow control frames for multi-frame answers must go to this module's request ID.
        sx("ATFCSH$hex")
        sx("ATFCSD300000")
        sx("ATFCSM1")
        currentHeader = hex
    }

    /** Positive-response payload of [command] sent to one module, or null (no answer / negative). */
    private suspend fun requestFrom(requestId: Int, command: String, timeoutMs: Long = 2_000): ByteArray? {
        target(requestId)
        val response = ElmResponseParser.parse(sx(command, timeoutMs), command) as? ObdResponse.Data ?: return null
        val service = command.substring(0, 2).toInt(16)
        val echo = ElmResponseParser.hexToBytes(command.substring(2))
        return response.messages.firstOrNull { m ->
            m.isNotEmpty() && m.u(0) == service + 0x40 && echo.indices.all { i -> i + 1 < m.size && m[i + 1] == echo[i] }
        }?.let { it.copyOfRange(1 + echo.size, it.size) }
    }

    /** Reads a manufacturer / custom sensor. */
    private suspend fun readCustom(pid: Pid, request: PidRequest): Double? {
        if (!supportsModuleAccess) return null
        return session {
            val data = requestFrom(request.headerId, request.command) ?: return@session null
            runCatching { pid.decode(IntArray(data.size) { data.u(it) }) }.getOrNull()?.takeUnless { it.isNaN() }
        }
    }

    /**
     * All-system scan: asks every candidate address on the main CAN bus whether a module is there
     * (TesterPresent), then reads each module's trouble codes. [onProgress] gets 0..1.
     *
     * Body modules on GM's single-wire low-speed bus (OBD pin 1) can't be reached by an ELM327.
     */
    suspend fun scanModules(full: Boolean = false, onProgress: (Float) -> Unit = {}): SystemScan {
        val log = StringBuilder()
        fun note(line: String) { log.appendLine(line) }
        if (!supportsModuleAccess) {
            return SystemScan(emptyList(), "Module scan needs an 11-bit CAN car (most 2008+ US vehicles).")
        }
        val candidates = buildList {
            addAll(0x7E0..0x7E7)
            addAll(0x240..0x25F) // GM high-speed GMLAN physical addresses
            if (full) addAll((0x700..0x7DF).filterNot { it in 0x7E0..0x7E7 })
        }
        return session {
            timeoutChanged = true
            sx("ATST19") // ~100 ms per silent address instead of ~200 ms
            val found = mutableListOf<Module>()
            candidates.forEachIndexed { i, id ->
                onProgress(i.toFloat() / (candidates.size * 2))
                val answer = runCatching { requestFrom(id, "3E00", timeoutMs = 500) }.getOrNull()
                if (answer != null) {
                    found += Module(id, responseIdFor(id))
                    note("found %03X -> %03X".format(id, responseIdFor(id)))
                }
            }
            sx("ATST32")
            note("modules: ${found.size}")
            val reports = found.mapIndexed { i, module ->
                onProgress(0.5f + i.toFloat() / (found.size * 2).coerceAtLeast(1))
                readModuleDtcs(module, ::note)
            }
            onProgress(1f)
            SystemScan(reports, log.toString())
        }
    }

    /** Must run inside [session]. Tries UDS first, then GM's older GMLAN service. */
    private suspend fun readModuleDtcs(module: Module, note: (String) -> Unit): ModuleReport {
        val uds = requestRaw(module.requestId, "1902FF", note)
        if (uds != null) {
            val positive = uds.firstOrNull { it.isNotEmpty() && it.u(0) == 0x59 }
            if (positive != null) return ModuleReport(module, ModuleDtcParser.parseUds(positive))
        }
        val gm = readGmlanDtcs(module, note)
        if (gm != null) return ModuleReport(module, gm)
        return ModuleReport(module, emptyList(), "This module didn't answer a trouble-code request the app knows.")
    }

    /** Like [requestFrom] but returns every message (including negative responses) and logs them. */
    private suspend fun requestRaw(requestId: Int, command: String, note: (String) -> Unit): List<ByteArray>? {
        target(requestId)
        val raw = sx(command, 3_000)
        note("%03X %s -> %s".format(requestId, command, raw.replace("\r", " | ").trim()))
        return (ElmResponseParser.parse(raw, command) as? ObdResponse.Data)?.messages
    }

    /**
     * GMLAN `$A9 81 12`: answers come as single raw frames on a different ID (0x5xx), without
     * ISO-TP framing, so this listens with CAN formatting off. Experimental.
     */
    private suspend fun readGmlanDtcs(module: Module, note: (String) -> Unit): List<ModuleDtc>? {
        try {
            headerChanged = true
            sx("ATSH%03X".format(module.requestId))
            sx("ATH1")
            sx("ATCAF0")
            sx("ATCF500")
            sx("ATCM700")
            currentHeader = null
            val raw = sx("03A98112", 3_000)
            note("%03X A98112 -> %s".format(module.requestId, raw.replace("\r", " | ").trim()))
            val frames = ElmResponseParser.lines(raw, "03A98112")
                .map { it.replace(" ", "").uppercase() }
                .filter { it.length >= 5 && it.all { c -> c.isDigit() || c in 'A'..'F' } }
                .map { ElmResponseParser.hexToBytes(it.substring(3)) } // drop the 3-digit header
            if (frames.none { it.isNotEmpty() && it.u(0) == 0x81 }) return null
            return frames.mapNotNull(ModuleDtcParser::parseGmlanFrame).distinct()
        } finally {
            runCatching { sx("ATCAF1") }
            runCatching { sx("ATH0") }
        }
    }

    /**
     * ID explorer: asks one module for every data identifier from [from] to [to] (service $22)
     * and returns the ones that answered with their raw bytes. Read-only.
     */
    suspend fun exploreDids(requestId: Int, from: Int, to: Int, onProgress: (Float) -> Unit = {}): Map<Int, ByteArray> {
        if (!supportsModuleAccess) return emptyMap()
        return session {
            timeoutChanged = true
            sx("ATST19")
            val found = linkedMapOf<Int, ByteArray>()
            for (did in from..to) {
                onProgress((did - from).toFloat() / (to - from + 1))
                val data = runCatching { requestFrom(requestId, "22%04X".format(did), timeoutMs = 500) }.getOrNull()
                if (data != null) found[did] = data
            }
            sx("ATST32")
            onProgress(1f)
            found
        }
    }

    /** Voltage at the OBD port, measured by the adapter itself (`ATRV`). */
    suspend fun batteryVoltage(): Double? =
        at("ATRV")?.uppercase()?.removeSuffix("V")?.trim()?.toDoubleOrNull()

    private suspend fun at(command: String): String? =
        ElmResponseParser.lines(send(command), command).firstOrNull()
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
