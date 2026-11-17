package com.obdscanner.app

import android.content.SharedPreferences
import com.obdscanner.core.CustomSensor
import com.obdscanner.core.Pids
import org.json.JSONArray
import org.json.JSONObject
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
    /** Sensors the user added (module address + command + formula). */
    val customSensors: List<CustomSensor> = emptyList(),
    /** Built-in manufacturer sensors (GM) on cars of that make. */
    val manufacturerExtras: Boolean = true,
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
            customSensors = parseSensors(prefs.getString("custom_sensors", null)),
            manufacturerExtras = prefs.getBoolean("manufacturer_extras", defaults.manufacturerExtras),
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
            .putString("custom_sensors", sensorsJson(s.customSensors))
            .putBoolean("manufacturer_extras", s.manufacturerExtras)
            .apply()
    }

    private fun sensorsJson(list: List<CustomSensor>): String = JSONArray().apply {
        list.forEach { c ->
            put(
                JSONObject()
                    .put("id", c.id).put("name", c.name).put("short", c.shortName).put("unit", c.unit)
                    .put("header", c.header).put("command", c.command).put("formula", c.formula)
                    .put("min", c.min).put("max", c.max),
            )
        }
    }.toString()

    private fun parseSensors(raw: String?): List<CustomSensor> = runCatching {
        val array = JSONArray(raw ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            runCatching {
                CustomSensor(
                    id = o.getInt("id"), name = o.getString("name"), shortName = o.optString("short"),
                    unit = o.optString("unit"), header = o.getString("header"), command = o.getString("command"),
                    formula = o.getString("formula"), min = o.optDouble("min", 0.0), max = o.optDouble("max", 100.0),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

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
