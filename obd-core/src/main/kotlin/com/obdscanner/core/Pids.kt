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
    val decode: (IntArray) -> Double,
) {
    val command: String get() = "01%02X".format(code)

    fun format(value: Double): String = "%.${decimals}f".format(value)
}

private fun a(d: IntArray) = d[0].toDouble()
private fun ab(d: IntArray) = (d[0] * 256 + d[1]).toDouble()

object Pids {
    val ENGINE_LOAD = Pid(0x04, "Calculated engine load", "Load", "%", 0.0, 100.0) { a(it) * 100 / 255 }
    val COOLANT_TEMP = Pid(0x05, "Engine coolant temperature", "Coolant", "°C", -40.0, 215.0) { a(it) - 40 }
    val STFT_B1 = Pid(0x06, "Short term fuel trim — bank 1", "STFT B1", "%", -100.0, 99.2, 1) { a(it) / 1.28 - 100 }
    val LTFT_B1 = Pid(0x07, "Long term fuel trim — bank 1", "LTFT B1", "%", -100.0, 99.2, 1) { a(it) / 1.28 - 100 }
    val STFT_B2 = Pid(0x08, "Short term fuel trim — bank 2", "STFT B2", "%", -100.0, 99.2, 1) { a(it) / 1.28 - 100 }
    val LTFT_B2 = Pid(0x09, "Long term fuel trim — bank 2", "LTFT B2", "%", -100.0, 99.2, 1) { a(it) / 1.28 - 100 }
    val FUEL_PRESSURE = Pid(0x0A, "Fuel pressure", "Fuel press", "kPa", 0.0, 765.0) { a(it) * 3 }
    val INTAKE_MAP = Pid(0x0B, "Intake manifold absolute pressure", "MAP", "kPa", 0.0, 255.0) { a(it) }
    val RPM = Pid(0x0C, "Engine speed", "RPM", "rpm", 0.0, 8000.0) { ab(it) / 4 }
    val SPEED = Pid(0x0D, "Vehicle speed", "Speed", "km/h", 0.0, 255.0) { a(it) }
    val TIMING_ADVANCE = Pid(0x0E, "Timing advance", "Timing", "°", -64.0, 63.5, 1) { a(it) / 2 - 64 }
    val INTAKE_TEMP = Pid(0x0F, "Intake air temperature", "Intake", "°C", -40.0, 215.0) { a(it) - 40 }
    val MAF = Pid(0x10, "Mass air flow rate", "MAF", "g/s", 0.0, 655.35, 2) { ab(it) / 100 }
    val THROTTLE = Pid(0x11, "Throttle position", "Throttle", "%", 0.0, 100.0) { a(it) * 100 / 255 }
    val RUN_TIME = Pid(0x1F, "Run time since engine start", "Run time", "s", 0.0, 65535.0) { ab(it) }
    val DISTANCE_MIL_ON = Pid(0x21, "Distance traveled with MIL on", "Dist. MIL", "km", 0.0, 65535.0) { ab(it) }
    val FUEL_LEVEL = Pid(0x2F, "Fuel tank level", "Fuel", "%", 0.0, 100.0) { a(it) * 100 / 255 }
    val DISTANCE_SINCE_CLEAR = Pid(0x31, "Distance since codes cleared", "Dist. clear", "km", 0.0, 65535.0) { ab(it) }
    val BARO_PRESSURE = Pid(0x33, "Barometric pressure", "Baro", "kPa", 0.0, 255.0) { a(it) }
    val MODULE_VOLTAGE = Pid(0x42, "Control module voltage", "Voltage", "V", 0.0, 65.5, 2) { ab(it) / 1000 }
    val RELATIVE_THROTTLE = Pid(0x45, "Relative throttle position", "Rel. throttle", "%", 0.0, 100.0) { a(it) * 100 / 255 }
    val AMBIENT_TEMP = Pid(0x46, "Ambient air temperature", "Ambient", "°C", -40.0, 215.0) { a(it) - 40 }
    val OIL_TEMP = Pid(0x5C, "Engine oil temperature", "Oil", "°C", -40.0, 210.0) { a(it) - 40 }
    val FUEL_RATE = Pid(0x5E, "Engine fuel rate", "Fuel rate", "L/h", 0.0, 3276.75, 2) { ab(it) / 20 }

    val ALL: List<Pid> = listOf(
        RPM, SPEED, COOLANT_TEMP, ENGINE_LOAD, THROTTLE, INTAKE_TEMP, INTAKE_MAP, MAF,
        TIMING_ADVANCE, FUEL_LEVEL, MODULE_VOLTAGE, OIL_TEMP, FUEL_RATE, AMBIENT_TEMP,
        BARO_PRESSURE, FUEL_PRESSURE, STFT_B1, LTFT_B1, STFT_B2, LTFT_B2, RELATIVE_THROTTLE,
        RUN_TIME, DISTANCE_MIL_ON, DISTANCE_SINCE_CLEAR,
    )

    private val byCode = ALL.associateBy { it.code }

    fun byCode(code: Int): Pid? = byCode[code]
}

/** Decoded PID 01 01: check-engine light and how many codes are stored. */
data class MonitorStatus(val milOn: Boolean, val dtcCount: Int)
