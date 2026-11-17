package com.obdscanner.core

/**
 * Fuel economy from speed and either the engine fuel rate PID or mass air flow.
 * MAF-based figures assume gasoline at the stoichiometric ratio, which is what
 * most generic scanners do.
 */
class TripComputer {
    var distanceKm = 0.0
        private set
    var fuelLiters = 0.0
        private set
    var instantFuelLh: Double? = null
        private set
    var instantSpeedKmh: Double? = null
        private set

    private var lastUpdateMs: Long? = null

    /** Feed the latest readings; call regularly (every polling round). */
    fun update(speedKmh: Double?, mafGs: Double?, fuelRateLh: Double?, nowMs: Long) {
        val fuelLh = fuelRateLh ?: mafGs?.let { fuelFromMaf(it) }
        val last = lastUpdateMs
        lastUpdateMs = nowMs
        instantFuelLh = fuelLh
        instantSpeedKmh = speedKmh
        if (last == null || speedKmh == null || fuelLh == null) return
        val hours = (nowMs - last) / 3_600_000.0
        if (hours <= 0 || hours > 0.01) return // ignore gaps longer than ~36 s (e.g. reconnects)
        distanceKm += speedKmh * hours
        fuelLiters += fuelLh * hours
    }

    fun reset() {
        distanceKm = 0.0
        fuelLiters = 0.0
        lastUpdateMs = null
    }

    /** L/100 km right now, or null when stopped (economy is meaningless at standstill). */
    val instantL100km: Double?
        get() {
            val speed = instantSpeedKmh ?: return null
            val fuel = instantFuelLh ?: return null
            return if (speed < 3) null else fuel / speed * 100
        }

    val averageL100km: Double? get() = if (distanceKm < 0.1) null else fuelLiters / distanceKm * 100

    companion object {
        private const val STOICH_GASOLINE = 14.7
        private const val GASOLINE_G_PER_L = 745.0

        fun fuelFromMaf(mafGs: Double): Double = mafGs / STOICH_GASOLINE / GASOLINE_G_PER_L * 3600

        fun l100kmToMpg(l100km: Double): Double = 235.214583 / l100km
    }
}
