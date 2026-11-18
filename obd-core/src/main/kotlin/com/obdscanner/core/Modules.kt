package com.obdscanner.core

/**
 * Where a manufacturer-specific value lives: the CAN ID to send to ([header], 11-bit, e.g. "7E2")
 * and the request bytes ([command], hex, e.g. "221940" = UDS/GMLAN read data by ID 0x1940).
 */
data class PidRequest(
    val header: String,
    val command: String,
    /** False until someone has confirmed the value is right on a real car. */
    val verified: Boolean = false,
) {
    init {
        require(header.matches(Regex("[0-9A-Fa-f]{3}"))) { "Header must be 3 hex digits (11-bit CAN ID), e.g. 7E0" }
        require(command.matches(Regex("([0-9A-Fa-f]{2}){1,7}"))) { "Command must be 1–7 bytes of hex, e.g. 221940" }
    }

    val headerId: Int get() = header.toInt(16)
    val commandBytes: Int get() = command.length / 2
}

/** A user-defined (or built-in manufacturer) sensor, stored as plain data. */
data class CustomSensor(
    val id: Int,
    val name: String,
    val shortName: String,
    val unit: String,
    val header: String,
    val command: String,
    val formula: String,
    val min: Double = 0.0,
    val max: Double = 100.0,
    val verified: Boolean = false,
) {
    /** Converts to a [Pid] the dashboard can poll. Codes from 0x10000 up never clash with Mode 01 PIDs. */
    fun toPid(): Pid {
        val f = Formula.parse(formula)
        return Pid(
            code = CUSTOM_BASE + id,
            name = name,
            shortName = shortName.ifBlank { name.take(12) },
            unit = unit,
            min = min,
            max = max,
            decimals = 1,
            request = PidRequest(header.uppercase(), command.uppercase(), verified),
        ) { f.evaluate(it) }
    }

    companion object {
        const val CUSTOM_BASE = 0x10000
    }
}

/**
 * Manufacturer-specific sensors that ship with the app. GM values come from public
 * enthusiast documentation and are marked unverified until confirmed on a car.
 */
object ManufacturerPids {
    val GM: List<CustomSensor> = listOf(
        CustomSensor(
            id = 0x8001, name = "Transmission fluid temperature (GM)", shortName = "Trans temp", unit = "°C",
            header = "7E2", command = "221940", formula = "A-40", min = -40.0, max = 215.0,
        ),
    )
}

/** A control module found by the all-system scan. */
data class Module(val requestId: Int, val responseId: Int) {
    val requestHex: String get() = "%03X".format(requestId)
    val responseHex: String get() = "%03X".format(responseId)

    /**
     * Best-known name. Only the engine and transmission addresses are named with confidence; every
     * other address is shown as its hex ID, because GM address→module mapping varies by vehicle and
     * guessing it would be inventing. The scan reads each module's real identity string separately.
     */
    val name: String get() = KNOWN[requestId] ?: "Module $requestHex"

    /**
     * Whether this looks like a safety-critical module (airbag, ABS/brake, steering) from its address.
     * Used only to keep the read-only security probe away from them; it is deliberately cautious.
     */
    val isSafetyCritical: Boolean get() = requestId in SAFETY_ADDRESSES

    companion object {
        private val KNOWN = mapOf(
            0x7E0 to "Engine (ECM)",
            0x7E2 to "Transmission (TCM)",
        )

        // GM high-speed GMLAN addresses commonly used by restraint (SDM) and brake (EBCM) modules.
        // Conservative: the security probe skips these, and also skips anything it can't identify.
        private val SAFETY_ADDRESSES = setOf(0x7E3, 0x242, 0x244, 0x247, 0x24A)
    }
}

/** Result of the read-only security-access discovery for one module. It never unlocks anything. */
enum class SecurityStatus(val label: String) {
    LOCKED("Locked — supports security access (seed received). No key is computed or sent."),
    UNLOCKED("Open — no key needed right now (already unlocked, or no seed required)."),
    NOT_SUPPORTED("No security access on this module."),
    DENIED("Request refused by the module."),
    SKIPPED_SAFETY("Skipped — safety-critical or unidentified module."),
    NO_ANSWER("No answer."),
}

enum class DtcProtocol(val label: String) { UDS("UDS"), GMLAN("GMLAN") }

/** A trouble code read from a specific module (3-byte DTC with failure type and status). */
data class ModuleDtc(
    val code: String,
    val failureType: Int,
    val status: Int,
    val protocol: DtcProtocol,
) {
    /** e.g. "C0035-5A" (code plus failure type, as dealer tools show it). */
    val fullCode: String get() = if (failureType == 0) code else "%s-%02X".format(code, failureType)
    val description: String get() = DtcDescriptions.describe(code)

    /** UDS status bit 0: failing right now. */
    val activeNow: Boolean get() = status and 0x01 != 0

    /** UDS status bit 3: confirmed (stored). */
    val confirmed: Boolean get() = status and 0x08 != 0
}

/** Result for one module in an all-system scan. */
data class ModuleReport(
    val module: Module,
    val dtcs: List<ModuleDtc>,
    /** Null when codes were read; otherwise why not. */
    val problem: String? = null,
    /** The module's own identity string (ECU name / part number), read-only. Null if it gave none. */
    val identity: String? = null,
    /** Set after a read-only security-access check is run on this module. */
    val security: SecurityStatus? = null,
)

/** Result of an all-system scan, including the raw exchange for troubleshooting. */
data class SystemScan(val reports: List<ModuleReport>, val log: String)

object ModuleDtcParser {
    /** UDS `59 02 <mask> [DTC_hi DTC_mid failure status]...` */
    fun parseUds(message: ByteArray): List<ModuleDtc> {
        if (message.size < 3 || message.u(0) != 0x59 || message.u(1) != 0x02) return emptyList()
        val out = mutableListOf<ModuleDtc>()
        var i = 3
        while (i + 3 < message.size) {
            val a = message.u(i)
            val b = message.u(i + 1)
            if (a != 0 || b != 0) out += ModuleDtc(Dtc.decode(a, b), message.u(i + 2), message.u(i + 3), DtcProtocol.UDS)
            i += 4
        }
        return out
    }

    /**
     * GMLAN `$A9` answers arrive as separate frames `81 DTC_hi DTC_lo failure status ...`;
     * an all-zero DTC ends the list. Experimental.
     */
    fun parseGmlanFrame(frame: ByteArray): ModuleDtc? {
        if (frame.size < 5 || frame.u(0) != 0x81) return null
        val a = frame.u(1)
        val b = frame.u(2)
        if (a == 0 && b == 0) return null
        return ModuleDtc(Dtc.decode(a, b), frame.u(3), frame.u(4), DtcProtocol.GMLAN)
    }
}
