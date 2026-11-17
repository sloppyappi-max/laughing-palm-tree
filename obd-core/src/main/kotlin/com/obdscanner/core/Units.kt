package com.obdscanner.core

enum class UnitSystem { METRIC, IMPERIAL }

/** A value ready to show: converted to the chosen unit system. */
data class DisplayValue(val value: Double, val unit: String, val decimals: Int) {
    val text: String get() = "%.${decimals}f".format(value)
}

object Units {
    /** Converts a PID's metric value for display. Pressures become psi, temperatures °F, and so on. */
    fun display(pid: Pid, value: Double, system: UnitSystem): DisplayValue {
        if (system == UnitSystem.METRIC) return DisplayValue(value, pid.unit, pid.decimals)
        return when (pid.unit) {
            "°C" -> DisplayValue(value * 9 / 5 + 32, "°F", pid.decimals)
            "km/h" -> DisplayValue(value / 1.609344, "mph", pid.decimals)
            "km" -> DisplayValue(value / 1.609344, "mi", pid.decimals)
            "kPa" -> DisplayValue(value * 0.1450377, "psi", 1)
            "L/h" -> DisplayValue(value / 3.785412, "gal/h", 2)
            "g/s" -> DisplayValue(value * 0.1322774, "lb/min", 2)
            else -> DisplayValue(value, pid.unit, pid.decimals)
        }
    }

    /** Top of a gauge scale for [pid] in [system]. */
    fun gaugeMax(pid: Pid, system: UnitSystem): Double = when {
        pid == Pids.RPM -> 7000.0
        pid == Pids.SPEED -> if (system == UnitSystem.IMPERIAL) 120.0 else 200.0
        else -> display(pid, pid.max, system).value
    }
}
