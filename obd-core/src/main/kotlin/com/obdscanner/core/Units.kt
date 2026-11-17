package com.obdscanner.core

enum class UnitSystem { METRIC, IMPERIAL }

/** A value ready to show: converted to the chosen unit system. */
data class DisplayValue(val value: Double, val unit: String, val text: String) {
    constructor(value: Double, unit: String, decimals: Int) : this(value, unit, "%.${decimals}f".format(value))
}

object Units {
    /** Converts a PID's metric value for display. Pressures become psi, temperatures °F, and so on. */
    fun display(pid: Pid, value: Double, system: UnitSystem): DisplayValue {
        pid.label?.let { return DisplayValue(value, "", it(value)) }
        if (system == UnitSystem.METRIC) return DisplayValue(value, pid.unit, pid.decimals)
        return when (pid.unit) {
            "°C" -> DisplayValue(value * 9 / 5 + 32, "°F", pid.decimals)
            "km/h" -> DisplayValue(value / 1.609344, "mph", pid.decimals)
            "km" -> DisplayValue(value / 1.609344, "mi", pid.decimals)
            "kPa" -> DisplayValue(value * 0.1450377, "psi", 1)
            "L/h" -> DisplayValue(value / 3.785412, "gal/h", 2)
            "g/s" -> DisplayValue(value * 0.1322774, "lb/min", 2)
            "Nm" -> DisplayValue(value * 0.7375621, "lb·ft", 0)
            else -> DisplayValue(value, pid.unit, pid.decimals)
        }
    }

    /** Inverse of [display]: turns a number typed in [system]'s unit back into the PID's metric unit. */
    fun toMetric(pid: Pid, shown: Double, system: UnitSystem): Double {
        if (system == UnitSystem.METRIC || pid.label != null) return shown
        return when (pid.unit) {
            "°C" -> (shown - 32) * 5 / 9
            "km/h", "km" -> shown * 1.609344
            "kPa" -> shown / 0.1450377
            "L/h" -> shown * 3.785412
            "g/s" -> shown / 0.1322774
            "Nm" -> shown / 0.7375621
            else -> shown
        }
    }

    /** Top of a gauge scale for [pid] in [system]. */
    fun gaugeMax(pid: Pid, system: UnitSystem): Double = when {
        pid == Pids.RPM -> 7000.0
        pid == Pids.SPEED -> if (system == UnitSystem.IMPERIAL) 120.0 else 200.0
        else -> display(pid, pid.max, system).value
    }
}
