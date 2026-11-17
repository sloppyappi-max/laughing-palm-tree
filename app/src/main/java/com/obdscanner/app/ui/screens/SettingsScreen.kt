package com.obdscanner.app.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.obdscanner.app.Accent
import com.obdscanner.app.AppSettings
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.ThemeMode
import com.obdscanner.app.UiState
import com.obdscanner.app.ui.theme.color
import com.obdscanner.core.Pids
import com.obdscanner.core.UnitSystem
import com.obdscanner.core.Units

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, viewModel: ObdViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val s = state.settings
    fun update(change: (AppSettings) -> AppSettings) = viewModel.updateSettings(change)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("Appearance") }
            item {
                SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Theme")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = s.theme == mode,
                                    onClick = { update { it.copy(theme = mode) } },
                                    label = { Text(mode.label) },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Accent color")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Accent.entries.forEach { accent ->
                                Swatch(
                                    color = accent.color(),
                                    selected = s.accent == accent && !s.dynamicColor,
                                    label = accent.label,
                                    onClick = { update { it.copy(accent = accent, dynamicColor = false) } },
                                )
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        HorizontalDivider()
                        SwitchRow("Match wallpaper colors", "Material You — overrides the accent color", s.dynamicColor) { on ->
                            update { it.copy(dynamicColor = on) }
                        }
                    }
                }
            }

            item { SectionTitle("Units") }
            item {
                SettingsCard {
                    SwitchRow(
                        "US units",
                        if (s.units == UnitSystem.IMPERIAL) "°F, mph, psi, miles, MPG" else "°C, km/h, kPa, km, L/100 km",
                        s.units == UnitSystem.IMPERIAL,
                    ) { on -> viewModel.setUnits(if (on) UnitSystem.IMPERIAL else UnitSystem.METRIC) }
                }
            }

            item { SectionTitle("Dashboard") }
            item {
                SettingsCard {
                    SwitchRow("Compact tiles", "Fit more sensors on screen", s.compactTiles) { on -> update { it.copy(compactTiles = on) } }
                    HorizontalDivider()
                    SwitchRow("Trend graphs", "Small graph under each sensor", s.showGraphs) { on -> update { it.copy(showGraphs = on) } }
                    HorizontalDivider()
                    SwitchRow("Keep screen on", "While the dashboard is open", s.keepScreenOn) { on -> update { it.copy(keepScreenOn = on) } }
                    HorizontalDivider()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Gauges and favorites")
                        Hint(
                            "Big gauges: " + (s.gauges.mapNotNull(Pids::byCode).joinToString { it.shortName }.ifEmpty { "none" }) +
                                ". ${s.favorites.size} favorite sensors. Tap any sensor on the dashboard to change these.",
                        )
                        TextButton(
                            onClick = { update { it.copy(favorites = AppSettings(it.units).favorites, gauges = AppSettings(it.units).gauges) } },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("Reset dashboard to defaults") }
                    }
                }
            }

            item { SectionTitle("Alerts") }
            item {
                SettingsCard {
                    if (s.alerts.isEmpty()) {
                        Column(Modifier.padding(16.dp)) {
                            Hint("No alerts set. Tap a sensor on the dashboard and set a low or high limit to get warned when it goes outside it.")
                        }
                    }
                    s.alerts.entries.sortedBy { it.key }.forEachIndexed { index, (code, alert) ->
                        val pid = Pids.byCode(code) ?: return@forEachIndexed
                        if (index > 0) HorizontalDivider()
                        fun show(v: Double) = Units.display(pid, v, s.units).let { "${it.text} ${it.unit}" }
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pid.name)
                                Hint(listOfNotNull(alert.low?.let { "below ${show(it)}" }, alert.high?.let { "above ${show(it)}" }).joinToString(" or "))
                            }
                            TextButton(onClick = { viewModel.setAlert(code, null, null) }) { Text("Remove") }
                        }
                    }
                }
            }

            item { SectionTitle("About") }
            item {
                SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("OBD Scanner")
                        Hint(
                            "Reads generic OBD-II data (SAE J1979): live sensors, trouble codes, freeze frame, readiness, " +
                                "self-test results and vehicle information, through an ELM327-compatible adapter.",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column { content() } }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Hint(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .background(color, CircleShape)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = "$label selected", tint = Color.Black)
    }
}
