package com.obdscanner.core

enum class DtcType(val mode: Int, val label: String) {
    STORED(0x03, "Stored"),
    PENDING(0x07, "Pending"),
    PERMANENT(0x0A, "Permanent"),
}

/** A diagnostic trouble code such as `P0301`. */
data class Dtc(val code: String, val type: DtcType) {
    val description: String get() = DtcDescriptions.describe(code)

    companion object {
        private val systems = charArrayOf('P', 'C', 'B', 'U')

        /** Decodes the two raw bytes of a DTC, e.g. `01 33` → `P0133`. */
        fun decode(a: Int, b: Int): String =
            "${systems[a shr 6]}${(a shr 4) and 0x3}${"%X".format(a and 0xF)}${"%02X".format(b)}"

        /**
         * Extracts the codes from the messages of a Mode 03/07/0A answer.
         *
         * CAN puts a count byte after the mode byte (so an odd number of bytes
         * follows it); older protocols send fixed groups of three codes padded
         * with `0000`.
         */
        fun parse(messages: List<ByteArray>, type: DtcType): List<Dtc> {
            val codes = LinkedHashSet<String>()
            for (m in messages) {
                var i = if ((m.size - 1) % 2 == 1) 2 else 1
                while (i + 1 < m.size) {
                    val a = m.u(i)
                    val b = m.u(i + 1)
                    if (a != 0 || b != 0) codes += decode(a, b)
                    i += 2
                }
            }
            return codes.map { Dtc(it, type) }
        }
    }
}

/** Plain-language descriptions for common generic (SAE J2012) codes. */
object DtcDescriptions {

    private val known: Map<String, String> = mapOf(
        "P0010" to "Camshaft position actuator circuit (bank 1)",
        "P0011" to "Camshaft timing over-advanced (bank 1)",
        "P0012" to "Camshaft timing over-retarded (bank 1)",
        "P0016" to "Crankshaft/camshaft position correlation (bank 1 sensor A)",
        "P0030" to "O2 sensor heater control circuit (bank 1 sensor 1)",
        "P0087" to "Fuel rail/system pressure too low",
        "P0088" to "Fuel rail/system pressure too high",
        "P0100" to "Mass air flow circuit malfunction",
        "P0101" to "Mass air flow circuit range/performance",
        "P0102" to "Mass air flow circuit low input",
        "P0103" to "Mass air flow circuit high input",
        "P0106" to "MAP/barometric pressure circuit range/performance",
        "P0107" to "MAP/barometric pressure circuit low input",
        "P0108" to "MAP/barometric pressure circuit high input",
        "P0110" to "Intake air temperature circuit malfunction",
        "P0112" to "Intake air temperature circuit low input",
        "P0113" to "Intake air temperature circuit high input",
        "P0115" to "Engine coolant temperature circuit malfunction",
        "P0116" to "Engine coolant temperature circuit range/performance",
        "P0117" to "Engine coolant temperature circuit low input",
        "P0118" to "Engine coolant temperature circuit high input",
        "P0120" to "Throttle position sensor circuit malfunction",
        "P0121" to "Throttle position sensor range/performance",
        "P0122" to "Throttle position sensor circuit low input",
        "P0123" to "Throttle position sensor circuit high input",
        "P0125" to "Insufficient coolant temperature for closed loop fuel control",
        "P0128" to "Coolant thermostat (temperature below regulating temperature)",
        "P0130" to "O2 sensor circuit (bank 1 sensor 1)",
        "P0131" to "O2 sensor circuit low voltage (bank 1 sensor 1)",
        "P0132" to "O2 sensor circuit high voltage (bank 1 sensor 1)",
        "P0133" to "O2 sensor circuit slow response (bank 1 sensor 1)",
        "P0134" to "O2 sensor circuit no activity (bank 1 sensor 1)",
        "P0135" to "O2 sensor heater circuit (bank 1 sensor 1)",
        "P0136" to "O2 sensor circuit (bank 1 sensor 2)",
        "P0137" to "O2 sensor circuit low voltage (bank 1 sensor 2)",
        "P0138" to "O2 sensor circuit high voltage (bank 1 sensor 2)",
        "P0140" to "O2 sensor circuit no activity (bank 1 sensor 2)",
        "P0141" to "O2 sensor heater circuit (bank 1 sensor 2)",
        "P0155" to "O2 sensor heater circuit (bank 2 sensor 1)",
        "P0161" to "O2 sensor heater circuit (bank 2 sensor 2)",
        "P0171" to "System too lean (bank 1)",
        "P0172" to "System too rich (bank 1)",
        "P0174" to "System too lean (bank 2)",
        "P0175" to "System too rich (bank 2)",
        "P0200" to "Injector circuit malfunction",
        "P0201" to "Injector circuit malfunction — cylinder 1",
        "P0202" to "Injector circuit malfunction — cylinder 2",
        "P0203" to "Injector circuit malfunction — cylinder 3",
        "P0204" to "Injector circuit malfunction — cylinder 4",
        "P0217" to "Engine overheat condition",
        "P0219" to "Engine overspeed condition",
        "P0234" to "Turbo/supercharger overboost condition",
        "P0299" to "Turbo/supercharger underboost",
        "P0300" to "Random/multiple cylinder misfire detected",
        "P0301" to "Cylinder 1 misfire detected",
        "P0302" to "Cylinder 2 misfire detected",
        "P0303" to "Cylinder 3 misfire detected",
        "P0304" to "Cylinder 4 misfire detected",
        "P0305" to "Cylinder 5 misfire detected",
        "P0306" to "Cylinder 6 misfire detected",
        "P0307" to "Cylinder 7 misfire detected",
        "P0308" to "Cylinder 8 misfire detected",
        "P0325" to "Knock sensor 1 circuit (bank 1)",
        "P0327" to "Knock sensor 1 circuit low input (bank 1)",
        "P0335" to "Crankshaft position sensor A circuit",
        "P0336" to "Crankshaft position sensor A circuit range/performance",
        "P0340" to "Camshaft position sensor circuit (bank 1)",
        "P0341" to "Camshaft position sensor circuit range/performance (bank 1)",
        "P0351" to "Ignition coil A primary/secondary circuit",
        "P0352" to "Ignition coil B primary/secondary circuit",
        "P0353" to "Ignition coil C primary/secondary circuit",
        "P0354" to "Ignition coil D primary/secondary circuit",
        "P0400" to "Exhaust gas recirculation flow malfunction",
        "P0401" to "Exhaust gas recirculation flow insufficient",
        "P0402" to "Exhaust gas recirculation flow excessive",
        "P0404" to "Exhaust gas recirculation circuit range/performance",
        "P0411" to "Secondary air injection system incorrect flow",
        "P0420" to "Catalyst system efficiency below threshold (bank 1)",
        "P0430" to "Catalyst system efficiency below threshold (bank 2)",
        "P0440" to "Evaporative emission control system malfunction",
        "P0441" to "Evaporative emission system incorrect purge flow",
        "P0442" to "Evaporative emission system small leak detected",
        "P0443" to "Evaporative emission purge control valve circuit",
        "P0446" to "Evaporative emission vent control circuit",
        "P0449" to "Evaporative emission vent valve/solenoid circuit",
        "P0451" to "Evaporative emission pressure sensor range/performance",
        "P0455" to "Evaporative emission system large leak detected",
        "P0456" to "Evaporative emission system very small leak detected",
        "P0461" to "Fuel level sensor circuit range/performance",
        "P0480" to "Cooling fan 1 control circuit",
        "P0500" to "Vehicle speed sensor malfunction",
        "P0505" to "Idle air control system malfunction",
        "P0506" to "Idle air control system RPM lower than expected",
        "P0507" to "Idle air control system RPM higher than expected",
        "P0520" to "Engine oil pressure sensor/switch circuit",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0571" to "Brake switch A circuit",
        "P0600" to "Serial communication link malfunction",
        "P0601" to "Internal control module memory checksum error",
        "P0606" to "Control module processor fault",
        "P0700" to "Transmission control system malfunction",
        "P0705" to "Transmission range sensor circuit malfunction",
        "P0715" to "Input/turbine speed sensor circuit",
        "P0720" to "Output speed sensor circuit",
        "P0730" to "Incorrect gear ratio",
        "P0740" to "Torque converter clutch circuit",
        "P0741" to "Torque converter clutch circuit performance or stuck off",
        "P0750" to "Shift solenoid A malfunction",
        "P0755" to "Shift solenoid B malfunction",
        "P2096" to "Post catalyst fuel trim system too lean (bank 1)",
        "P2097" to "Post catalyst fuel trim system too rich (bank 1)",
        "P2135" to "Throttle/pedal position sensor A/B voltage correlation",
        "P2187" to "System too lean at idle (bank 1)",
        "P2188" to "System too rich at idle (bank 1)",
        "P2195" to "O2 sensor signal stuck lean (bank 1 sensor 1)",
        "P2196" to "O2 sensor signal stuck rich (bank 1 sensor 1)",
        "P2270" to "O2 sensor signal stuck lean (bank 1 sensor 2)",
        "P2271" to "O2 sensor signal stuck rich (bank 1 sensor 2)",
        "U0100" to "Lost communication with ECM/PCM A",
        "U0101" to "Lost communication with TCM",
        "U0121" to "Lost communication with ABS control module",
        "U0140" to "Lost communication with body control module",
        "U0155" to "Lost communication with instrument panel cluster",
    )

    /** A specific description when known, otherwise the system/subsystem the code belongs to. */
    fun describe(code: String): String {
        known[code]?.let { return it }
        if (code.length != 5) return "Unknown code"
        val system = when (code[0]) {
            'P' -> "Powertrain"
            'C' -> "Chassis"
            'B' -> "Body"
            'U' -> "Network"
            else -> return "Unknown code"
        }
        val scope = if (code[1] == '0' || code[1] == '2') "generic" else "manufacturer-specific"
        val area = if (code[0] == 'P' && (code[1] == '0' || code[1] == '1')) {
            when (code[2]) {
                '0', '1', '2' -> "fuel and air metering"
                '3' -> "ignition system or misfire"
                '4' -> "auxiliary emission controls"
                '5' -> "vehicle speed, idle control or auxiliary inputs"
                '6' -> "computer or output circuit"
                '7', '8', '9' -> "transmission"
                else -> null
            }
        } else null
        return listOfNotNull("$system ($scope)", area).joinToString(" — ")
    }
}
