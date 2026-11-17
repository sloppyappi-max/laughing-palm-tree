package com.obdscanner.core

/** A Mode 01 (current data) parameter and how to turn its bytes into a value. */
data class Pid(
    val code: Int,
    val name: String,
    val shortName: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decimals: Int = 0,
    /** For status PIDs: turns the decoded number into words (e.g. fuel system "Closed loop"). */
    val label: ((Double) -> String)? = null,
    /** Set for manufacturer / custom sensors: which module to ask and what to send. Null = standard Mode 01. */
    val request: PidRequest? = null,
    val decode: (IntArray) -> Double,
) {
    val command: String get() = request?.command ?: "01%02X".format(code)
    val isCustom: Boolean get() = request != null

    fun format(value: Double): String = label?.invoke(value) ?: "%.${decimals}f".format(value)
}

private fun a(d: IntArray) = d[0].toDouble()
private fun ab(d: IntArray) = (d[0] * 256 + d[1]).toDouble()
private fun abSigned(d: IntArray) = (d[0] * 256 + d[1]).let { if (it >= 0x8000) it - 0x10000 else it }.toDouble()
private fun pct(d: IntArray) = d[0] * 100.0 / 255
private fun temp(d: IntArray) = d[0] - 40.0
private fun trim(d: IntArray) = d[0] / 1.28 - 100

private fun labels(vararg pairs: Pair<Int, String>): (Double) -> String {
    val map = pairs.toMap()
    return { v -> map[v.toInt()] ?: "Code ${v.toInt()}" }
}

private val FUEL_SYSTEM = labels(
    0 to "Not reported", 1 to "Open loop (warming up)", 2 to "Closed loop", 4 to "Open loop (load/decel)",
    8 to "Open loop (system fault)", 16 to "Closed loop (sensor fault)",
)
private val SECONDARY_AIR = labels(1 to "Upstream", 2 to "Downstream of catalyst", 4 to "Off / outside", 8 to "On for diagnostics")
private val OBD_STANDARD = labels(
    1 to "OBD-II (CARB)", 2 to "OBD (EPA)", 3 to "OBD and OBD-II", 4 to "OBD-I", 5 to "Not OBD compliant",
    6 to "EOBD", 7 to "EOBD and OBD-II", 8 to "EOBD and OBD", 9 to "EOBD, OBD and OBD-II", 10 to "JOBD",
    11 to "JOBD and OBD-II", 12 to "JOBD and EOBD", 13 to "JOBD, EOBD and OBD-II", 17 to "EMD", 18 to "EMD+",
    19 to "HD OBD-C", 20 to "HD OBD", 21 to "WWH OBD", 23 to "HD EOBD-I", 25 to "HD EOBD-II", 28 to "OBDBr-1",
    29 to "OBDBr-2", 30 to "KOBD", 31 to "IOBD I", 32 to "IOBD II", 33 to "HD EOBD-IV",
)
private val FUEL_TYPE = labels(
    0 to "Not available", 1 to "Gasoline", 2 to "Methanol", 3 to "Ethanol", 4 to "Diesel", 5 to "LPG", 6 to "CNG",
    7 to "Propane", 8 to "Electric", 9 to "Bifuel: gasoline", 10 to "Bifuel: methanol", 11 to "Bifuel: ethanol",
    12 to "Bifuel: LPG", 13 to "Bifuel: CNG", 14 to "Bifuel: propane", 15 to "Bifuel: electricity",
    16 to "Bifuel: electric + combustion", 17 to "Hybrid gasoline", 18 to "Hybrid ethanol", 19 to "Hybrid diesel",
    20 to "Hybrid electric", 21 to "Hybrid electric + combustion", 22 to "Hybrid regenerative", 23 to "Bifuel: diesel",
)
private val ON_OFF = labels(0 to "Off", 1 to "On")

/** All standard SAE J1979 Mode 01 PIDs that hold a single readable value. */
object Pids {
    val FUEL_SYSTEM_STATUS = Pid(0x03, "Fuel system status", "Fuel system", "", 0.0, 16.0, label = FUEL_SYSTEM) { a(it) }
    val ENGINE_LOAD = Pid(0x04, "Calculated engine load", "Load", "%", 0.0, 100.0) { pct(it) }
    val COOLANT_TEMP = Pid(0x05, "Engine coolant temperature", "Coolant", "°C", -40.0, 215.0) { temp(it) }
    val STFT_B1 = Pid(0x06, "Short term fuel trim — bank 1", "STFT B1", "%", -100.0, 99.2, 1) { trim(it) }
    val LTFT_B1 = Pid(0x07, "Long term fuel trim — bank 1", "LTFT B1", "%", -100.0, 99.2, 1) { trim(it) }
    val STFT_B2 = Pid(0x08, "Short term fuel trim — bank 2", "STFT B2", "%", -100.0, 99.2, 1) { trim(it) }
    val LTFT_B2 = Pid(0x09, "Long term fuel trim — bank 2", "LTFT B2", "%", -100.0, 99.2, 1) { trim(it) }
    val FUEL_PRESSURE = Pid(0x0A, "Fuel pressure", "Fuel press", "kPa", 0.0, 765.0) { a(it) * 3 }
    val INTAKE_MAP = Pid(0x0B, "Intake manifold absolute pressure", "MAP", "kPa", 0.0, 255.0) { a(it) }
    val RPM = Pid(0x0C, "Engine speed", "RPM", "rpm", 0.0, 8000.0) { ab(it) / 4 }
    val SPEED = Pid(0x0D, "Vehicle speed", "Speed", "km/h", 0.0, 255.0) { a(it) }
    val TIMING_ADVANCE = Pid(0x0E, "Timing advance", "Timing", "°", -64.0, 63.5, 1) { a(it) / 2 - 64 }
    val INTAKE_TEMP = Pid(0x0F, "Intake air temperature", "Intake", "°C", -40.0, 215.0) { temp(it) }
    val MAF = Pid(0x10, "Mass air flow rate", "MAF", "g/s", 0.0, 655.35, 2) { ab(it) / 100 }
    val THROTTLE = Pid(0x11, "Throttle position", "Throttle", "%", 0.0, 100.0) { pct(it) }
    val SECONDARY_AIR_STATUS = Pid(0x12, "Commanded secondary air status", "Sec. air", "", 0.0, 8.0, label = SECONDARY_AIR) { a(it) }

    private fun o2Voltage(code: Int, where: String) =
        Pid(code, "Oxygen sensor voltage — $where", "O2 $where", "V", 0.0, 1.275, 3) { a(it) / 200 }
    val O2_B1S1 = o2Voltage(0x14, "B1S1")
    val O2_B1S2 = o2Voltage(0x15, "B1S2")
    val O2_B1S3 = o2Voltage(0x16, "B1S3")
    val O2_B1S4 = o2Voltage(0x17, "B1S4")
    val O2_B2S1 = o2Voltage(0x18, "B2S1")
    val O2_B2S2 = o2Voltage(0x19, "B2S2")
    val O2_B2S3 = o2Voltage(0x1A, "B2S3")
    val O2_B2S4 = o2Voltage(0x1B, "B2S4")

    val OBD_STANDARD_PID = Pid(0x1C, "OBD standard this vehicle conforms to", "OBD standard", "", 0.0, 255.0, label = OBD_STANDARD) { a(it) }
    val PTO_STATUS = Pid(0x1E, "Power take-off (PTO) status", "PTO", "", 0.0, 1.0, label = ON_OFF) { (it[0] and 1).toDouble() }
    val RUN_TIME = Pid(0x1F, "Run time since engine start", "Run time", "s", 0.0, 65535.0) { ab(it) }
    val DISTANCE_MIL_ON = Pid(0x21, "Distance traveled with MIL on", "Dist. MIL", "km", 0.0, 65535.0) { ab(it) }
    val FUEL_RAIL_PRESSURE_REL = Pid(0x22, "Fuel rail pressure (relative to manifold)", "Rail press", "kPa", 0.0, 5177.0, 1) { ab(it) * 0.079 }
    val FUEL_RAIL_PRESSURE = Pid(0x23, "Fuel rail gauge pressure (direct injection)", "Rail press", "kPa", 0.0, 655350.0) { ab(it) * 10 }

    private fun lambda(code: Int, sensor: Int) =
        Pid(code, "Oxygen sensor $sensor air-fuel ratio (λ)", "O2 S$sensor λ", "λ", 0.0, 2.0, 3) { ab(it) * 2 / 65536 }
    private val LAMBDAS = (0..7).map { lambda(0x24 + it, it + 1) }

    val COMMANDED_EGR = Pid(0x2C, "Commanded EGR", "Cmd EGR", "%", 0.0, 100.0) { pct(it) }
    val EGR_ERROR = Pid(0x2D, "EGR error", "EGR error", "%", -100.0, 99.2, 1) { a(it) * 100 / 128 - 100 }
    val EVAP_PURGE = Pid(0x2E, "Commanded evaporative purge", "EVAP purge", "%", 0.0, 100.0) { pct(it) }
    val FUEL_LEVEL = Pid(0x2F, "Fuel tank level", "Fuel", "%", 0.0, 100.0) { pct(it) }
    val WARMUPS_SINCE_CLEAR = Pid(0x30, "Warm-ups since codes cleared", "Warm-ups", "", 0.0, 255.0) { a(it) }
    val DISTANCE_SINCE_CLEAR = Pid(0x31, "Distance since codes cleared", "Dist. clear", "km", 0.0, 65535.0) { ab(it) }
    val EVAP_VAPOR_PRESSURE = Pid(0x32, "Evap. system vapor pressure", "EVAP press", "Pa", -8192.0, 8192.0, 1) { abSigned(it) / 4 }
    val BARO_PRESSURE = Pid(0x33, "Barometric pressure", "Baro", "kPa", 0.0, 255.0) { a(it) }

    private fun lambdaCurrent(code: Int, sensor: Int) =
        Pid(code, "Oxygen sensor $sensor current", "O2 S$sensor mA", "mA", -128.0, 128.0, 2) { (it[2] * 256 + it[3]) / 256.0 - 128 }
    private val LAMBDA_CURRENTS = (0..7).map { lambdaCurrent(0x34 + it, it + 1) }

    private fun catTemp(code: Int, where: String) =
        Pid(code, "Catalyst temperature — $where", "Cat $where", "°C", -40.0, 6513.5, 1) { ab(it) / 10 - 40 }
    val CAT_TEMP_B1S1 = catTemp(0x3C, "B1S1")
    val CAT_TEMP_B2S1 = catTemp(0x3D, "B2S1")
    val CAT_TEMP_B1S2 = catTemp(0x3E, "B1S2")
    val CAT_TEMP_B2S2 = catTemp(0x3F, "B2S2")

    val MODULE_VOLTAGE = Pid(0x42, "Control module voltage", "Voltage", "V", 0.0, 65.5, 2) { ab(it) / 1000 }
    val ABSOLUTE_LOAD = Pid(0x43, "Absolute load value", "Abs. load", "%", 0.0, 25700.0) { ab(it) * 100 / 255 }
    val COMMANDED_LAMBDA = Pid(0x44, "Commanded air-fuel equivalence ratio (λ)", "Cmd λ", "λ", 0.0, 2.0, 3) { ab(it) * 2 / 65536 }
    val RELATIVE_THROTTLE = Pid(0x45, "Relative throttle position", "Rel. throttle", "%", 0.0, 100.0) { pct(it) }
    val AMBIENT_TEMP = Pid(0x46, "Ambient air temperature", "Ambient", "°C", -40.0, 215.0) { temp(it) }
    val THROTTLE_B = Pid(0x47, "Absolute throttle position B", "Throttle B", "%", 0.0, 100.0) { pct(it) }
    val THROTTLE_C = Pid(0x48, "Absolute throttle position C", "Throttle C", "%", 0.0, 100.0) { pct(it) }
    val PEDAL_D = Pid(0x49, "Accelerator pedal position D", "Pedal D", "%", 0.0, 100.0) { pct(it) }
    val PEDAL_E = Pid(0x4A, "Accelerator pedal position E", "Pedal E", "%", 0.0, 100.0) { pct(it) }
    val PEDAL_F = Pid(0x4B, "Accelerator pedal position F", "Pedal F", "%", 0.0, 100.0) { pct(it) }
    val THROTTLE_ACTUATOR = Pid(0x4C, "Commanded throttle actuator", "Cmd throttle", "%", 0.0, 100.0) { pct(it) }
    val TIME_MIL_ON = Pid(0x4D, "Time run with MIL on", "Time MIL", "min", 0.0, 65535.0) { ab(it) }
    val TIME_SINCE_CLEAR = Pid(0x4E, "Time since codes cleared", "Time clear", "min", 0.0, 65535.0) { ab(it) }
    val FUEL_TYPE_PID = Pid(0x51, "Fuel type", "Fuel type", "", 0.0, 23.0, label = FUEL_TYPE) { a(it) }
    val ETHANOL = Pid(0x52, "Ethanol fuel percentage", "Ethanol", "%", 0.0, 100.0) { pct(it) }
    val EVAP_ABS_PRESSURE = Pid(0x53, "Absolute evap. system vapor pressure", "EVAP abs", "kPa", 0.0, 327.675, 2) { ab(it) / 200 }
    val EVAP_PRESSURE_WIDE = Pid(0x54, "Evap. system vapor pressure (wide)", "EVAP press", "Pa", -32768.0, 32767.0) { abSigned(it) }
    val ST_SEC_O2_B1 = Pid(0x55, "Short term secondary O2 trim — bank 1", "ST O2 B1", "%", -100.0, 99.2, 1) { a(it) * 100 / 128 - 100 }
    val LT_SEC_O2_B1 = Pid(0x56, "Long term secondary O2 trim — bank 1", "LT O2 B1", "%", -100.0, 99.2, 1) { a(it) * 100 / 128 - 100 }
    val ST_SEC_O2_B2 = Pid(0x57, "Short term secondary O2 trim — bank 2", "ST O2 B2", "%", -100.0, 99.2, 1) { a(it) * 100 / 128 - 100 }
    val LT_SEC_O2_B2 = Pid(0x58, "Long term secondary O2 trim — bank 2", "LT O2 B2", "%", -100.0, 99.2, 1) { a(it) * 100 / 128 - 100 }
    val FUEL_RAIL_ABS_PRESSURE = Pid(0x59, "Fuel rail absolute pressure", "Rail abs", "kPa", 0.0, 655350.0) { ab(it) * 10 }
    val RELATIVE_PEDAL = Pid(0x5A, "Relative accelerator pedal position", "Rel. pedal", "%", 0.0, 100.0) { pct(it) }
    val HYBRID_BATTERY = Pid(0x5B, "Hybrid battery pack remaining life", "Hybrid batt", "%", 0.0, 100.0) { pct(it) }
    val OIL_TEMP = Pid(0x5C, "Engine oil temperature", "Oil", "°C", -40.0, 210.0) { temp(it) }
    val INJECTION_TIMING = Pid(0x5D, "Fuel injection timing", "Inj. timing", "°", -210.0, 302.0, 2) { ab(it) / 128 - 210 }
    val FUEL_RATE = Pid(0x5E, "Engine fuel rate", "Fuel rate", "L/h", 0.0, 3276.75, 2) { ab(it) / 20 }
    val DEMAND_TORQUE = Pid(0x61, "Driver's demand engine torque", "Demand torque", "%", -125.0, 130.0) { a(it) - 125 }
    val ACTUAL_TORQUE = Pid(0x62, "Actual engine torque", "Torque", "%", -125.0, 130.0) { a(it) - 125 }
    val REFERENCE_TORQUE = Pid(0x63, "Engine reference torque", "Ref. torque", "Nm", 0.0, 65535.0) { ab(it) }
    val ODOMETER = Pid(0xA6, "Odometer", "Odometer", "km", 0.0, 429496729.5, 1) {
        ((it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()) / 10.0
    }

    val ALL: List<Pid> = listOf(
        RPM, SPEED, COOLANT_TEMP, ENGINE_LOAD, THROTTLE, INTAKE_TEMP, INTAKE_MAP, MAF,
        TIMING_ADVANCE, FUEL_LEVEL, MODULE_VOLTAGE, OIL_TEMP, FUEL_RATE, AMBIENT_TEMP,
        BARO_PRESSURE, FUEL_PRESSURE, STFT_B1, LTFT_B1, STFT_B2, LTFT_B2, RELATIVE_THROTTLE,
        FUEL_SYSTEM_STATUS, SECONDARY_AIR_STATUS,
        O2_B1S1, O2_B1S2, O2_B1S3, O2_B1S4, O2_B2S1, O2_B2S2, O2_B2S3, O2_B2S4,
    ) + LAMBDAS + LAMBDA_CURRENTS + listOf(
        CAT_TEMP_B1S1, CAT_TEMP_B2S1, CAT_TEMP_B1S2, CAT_TEMP_B2S2,
        ABSOLUTE_LOAD, COMMANDED_LAMBDA, THROTTLE_B, THROTTLE_C, PEDAL_D, PEDAL_E, PEDAL_F, THROTTLE_ACTUATOR,
        RELATIVE_PEDAL, COMMANDED_EGR, EGR_ERROR, EVAP_PURGE, EVAP_VAPOR_PRESSURE, EVAP_ABS_PRESSURE, EVAP_PRESSURE_WIDE,
        FUEL_RAIL_PRESSURE_REL, FUEL_RAIL_PRESSURE, FUEL_RAIL_ABS_PRESSURE, INJECTION_TIMING, ETHANOL, FUEL_TYPE_PID,
        ST_SEC_O2_B1, LT_SEC_O2_B1, ST_SEC_O2_B2, LT_SEC_O2_B2,
        DEMAND_TORQUE, ACTUAL_TORQUE, REFERENCE_TORQUE, HYBRID_BATTERY, PTO_STATUS,
        RUN_TIME, DISTANCE_MIL_ON, DISTANCE_SINCE_CLEAR, TIME_MIL_ON, TIME_SINCE_CLEAR, WARMUPS_SINCE_CLEAR,
        ODOMETER, OBD_STANDARD_PID,
    )

    /** Shown on the dashboard until the user picks their own. */
    val DEFAULT_FAVORITES: Set<Int> = listOf(
        RPM, SPEED, COOLANT_TEMP, ENGINE_LOAD, THROTTLE, INTAKE_TEMP, MAF, FUEL_LEVEL, MODULE_VOLTAGE,
        TIMING_ADVANCE, STFT_B1, LTFT_B1, OIL_TEMP, FUEL_RATE, FUEL_SYSTEM_STATUS, CAT_TEMP_B1S1,
    ).map { it.code }.toSet()

    private val byCode = ALL.associateBy { it.code }

    fun byCode(code: Int): Pid? = byCode[code]
}

/** One emissions readiness monitor from PID 01 01 (what an inspection station checks). */
data class ReadinessMonitor(val name: String, val complete: Boolean)

/** Decoded PID 01 01: check-engine light, how many codes are stored, and readiness monitors. */
data class MonitorStatus(
    val milOn: Boolean,
    val dtcCount: Int,
    val monitors: List<ReadinessMonitor> = emptyList(),
    val compressionIgnition: Boolean = false,
) {
    val incompleteMonitors: List<ReadinessMonitor> get() = monitors.filterNot { it.complete }

    /**
     * Rough smog-check verdict: most US states fail a car with the MIL on, and allow at most one
     * incomplete monitor (model year 2001+). Local rules vary.
     */
    val likelyPassesInspection: Boolean get() = !milOn && incompleteMonitors.size <= 1

    companion object {
        private val CONTINUOUS = listOf("Misfire", "Fuel system", "Comprehensive components")
        private val SPARK = listOf(
            "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air system",
            "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR system",
        )
        private val COMPRESSION = listOf(
            "NMHC catalyst", "NOx/SCR aftertreatment", null, "Boost pressure",
            null, "Exhaust gas sensor", "Particulate filter", "EGR/VVT system",
        )

        /** Decodes the four data bytes A B C D of PID 01 01. */
        fun decode(a: Int, b: Int, c: Int, d: Int): MonitorStatus {
            val diesel = (b and 0x08) != 0
            val monitors = mutableListOf<ReadinessMonitor>()
            CONTINUOUS.forEachIndexed { bit, name ->
                // Availability in B bits 0-2, incompleteness in B bits 4-6.
                if ((b shr bit) and 1 == 1) monitors += ReadinessMonitor(name, (b shr (bit + 4)) and 1 == 0)
            }
            (if (diesel) COMPRESSION else SPARK).forEachIndexed { bit, name ->
                // Availability in C, incompleteness in D.
                if (name != null && (c shr bit) and 1 == 1) monitors += ReadinessMonitor(name, (d shr bit) and 1 == 0)
            }
            return MonitorStatus((a and 0x80) != 0, a and 0x7F, monitors, diesel)
        }
    }
}
