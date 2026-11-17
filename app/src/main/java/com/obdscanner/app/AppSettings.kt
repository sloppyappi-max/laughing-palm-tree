package com.obdscanner.app

import android.content.SharedPreferences
import com.obdscanner.core.Pids
import com.obdscanner.core.UnitSystem
import java.util.Locale

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class Accent(val label: String, val argb: Long) {
    AMBER("Amber", 0xFFFFB300),
    BLUE("Blue", 0xFF42A5F5),
    TEAL("Teal", 0xFF26A69A),
    GREEN("Green", 0xFF66BB6A),
    RED("Red", 0xFFEF5350),
    PURPLE("Purple", 0xFFAB47BC),
}

/** A user-set warning range for one sensor, stored in the PID's metric unit. */
data class SensorAlert(val low: Double?, val high: Double?) {
    fun isTriggered(value: Double): Boolean = (low != null && value < low) || (high != null && value > high)
}

/** Everything the user can customize. Persisted in SharedPreferences. */
data class AppSettings(
    val units: UnitSystem,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.AMBER,
    /** Material You: take colors from the wallpaper (Android 12+). Overrides [accent]. */
    val dynamicColor: Boolean = false,
    val keepScreenOn: Boolean = true,
    val compactTiles: Boolean = false,
    val showGraphs: Boolean = true,
    /** Favorite sensors in dashboard order. */
    val favorites: List<Int> = Pids.ALL.map { it.code }.filter { it in Pids.DEFAULT_FAVORITES },
    /** Sensors shown as big round gauges at the top of the dashboard. */
    val gauges: List<Int> = listOf(Pids.RPM.code, Pids.SPEED.code),
    val alerts: Map<Int, SensorAlert> = emptyMap(),
)

class SettingsStore(private val prefs: SharedPreferences) {

    fun load(): AppSettings {
        val defaults = AppSettings(
            units = if (Locale.getDefault().country in setOf("US", "LR", "MM")) UnitSystem.IMPERIAL else UnitSystem.METRIC,
        )
        return AppSettings(
            units = enumOr(prefs.getString("units", null), defaults.units),
            theme = enumOr(prefs.getString("theme", null), defaults.theme),
            accent = enumOr(prefs.getString("accent", null), defaults.accent),
            dynamicColor = prefs.getBoolean("dynamic_color", defaults.dynamicColor),
            keepScreenOn = prefs.getBoolean("keep_screen_on", defaults.keepScreenOn),
            compactTiles = prefs.getBoolean("compact_tiles", defaults.compactTiles),
            showGraphs = prefs.getBoolean("show_graphs", defaults.showGraphs),
            favorites = intList(prefs.getString("favorites_order", null)) ?: legacyFavorites() ?: defaults.favorites,
            gauges = intList(prefs.getString("gauges", null)) ?: defaults.gauges,
            alerts = parseAlerts(prefs.getString("alerts", null)),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("units", s.units.name)
            .putString("theme", s.theme.name)
            .putString("accent", s.accent.name)
            .putBoolean("dynamic_color", s.dynamicColor)
            .putBoolean("keep_screen_on", s.keepScreenOn)
            .putBoolean("compact_tiles", s.compactTiles)
            .putBoolean("show_graphs", s.showGraphs)
            .putString("favorites_order", s.favorites.joinToString(","))
            .putString("gauges", s.gauges.joinToString(","))
            .putString("alerts", s.alerts.entries.joinToString(";") { (pid, a) -> "$pid:${a.low ?: ""}:${a.high ?: ""}" })
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> runCatching { enumValueOf<E>(n) }.getOrNull() } ?: default

    private fun intList(raw: String?): List<Int>? =
        raw?.split(",")?.mapNotNull { it.trim().toIntOrNull() }

    /** Favorites from the earlier version, which stored an unordered set. */
    private fun legacyFavorites(): List<Int>? =
        prefs.getStringSet("favorites", null)?.mapNotNull { it.toIntOrNull() }?.let { set ->
            Pids.ALL.map { it.code }.filter { it in set }
        }

    private fun parseAlerts(raw: String?): Map<Int, SensorAlert> =
        raw.orEmpty().split(";").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 3) return@mapNotNull null
            val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
            val alert = SensorAlert(parts[1].toDoubleOrNull(), parts[2].toDoubleOrNull())
            if (alert.low == null && alert.high == null) null else pid to alert
        }.toMap()
}
