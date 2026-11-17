package com.obdscanner.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.SensorAlert
import com.obdscanner.app.UiState
import com.obdscanner.app.ui.theme.Danger
import com.obdscanner.core.Pid
import com.obdscanner.core.Pids
import com.obdscanner.core.TripComputer
import com.obdscanner.core.UnitSystem
import com.obdscanner.core.Units

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: UiState,
    viewModel: ObdViewModel,
    connection: ConnectionState.Connected,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    // Poll only while this screen is visible and the app is in the foreground.
    LifecycleResumeEffect(Unit) {
        viewModel.setLiveDataActive(true)
        onPauseOrDispose { viewModel.setLiveDataActive(false) }
    }
    val view = LocalView.current
    DisposableEffect(view, settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    if (!connection.info.vehicleResponding) {
        NotRespondingCard(state, connection, onRetry = viewModel::retryVehicle, modifier = modifier)
        return
    }

    val gauges = viewModel.gaugePids(state)
    val pids = viewModel.dashboardPids(state)
    val tiles = pids - gauges.toSet()
    val alerts = state.triggeredAlerts
    var detail by remember { mutableStateOf<Pid?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (settings.compactTiles) 112.dp else 150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (settings.compactTiles) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (settings.compactTiles) 8.dp else 12.dp),
    ) {
        if (alerts.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "alerts") { AlertBanner(alerts, state.units) }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "filter") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.showAllSensors,
                    onClick = { viewModel.setShowAllSensors(false) },
                    label = { Text("Favorites") },
                )
                FilterChip(
                    selected = state.showAllSensors,
                    onClick = { viewModel.setShowAllSensors(true) },
                    label = { Text("All sensors (${viewModel.dashboardPids(state.copy(showAllSensors = true)).size})") },
                )
            }
        }
        if (state.trip.available) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "trip") { TripCard(state, onReset = viewModel::resetTrip) }
        }
        items(gauges, key = { "g${it.code}" }) { pid ->
            val value = state.live[pid.code]
            Box(Modifier.clickable { detail = pid }) {
                Gauge(pid, value, state.units, alerting = isAlerting(settings.alerts[pid.code], value))
            }
        }
        if (tiles.isEmpty() && gauges.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Hint(
                    if (state.showAllSensors) "This car reports no live data the app knows how to show."
                    else "No favorites yet. Open \"All sensors\", tap a sensor and add it to your favorites.",
                )
            }
        }
        items(tiles, key = { it.code }) { pid ->
            val value = state.live[pid.code]
            Box(Modifier.clickable { detail = pid }) {
                ValueTile(
                    pid = pid,
                    value = value,
                    units = state.units,
                    history = if (settings.showGraphs) state.history[pid.code].orEmpty() else emptyList(),
                    compact = settings.compactTiles,
                    alerting = isAlerting(settings.alerts[pid.code], value),
                    favorite = state.showAllSensors && pid.code in state.favorites,
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "hint") {
            Hint("Tap any sensor for a bigger graph, favorites, gauges and alerts.")
        }
    }

    detail?.let { pid ->
        SensorDetailDialog(
            pid = pid,
            state = state,
            viewModel = viewModel,
            onDismiss = { detail = null },
        )
    }
}

private fun isAlerting(alert: SensorAlert?, value: Double?) = alert != null && value != null && alert.isTriggered(value)

/** Lists every sensor that is outside the limits the user set. */
@Composable
private fun AlertBanner(alerts: List<Pair<Pid, Double>>, units: UnitSystem) {
    Card(colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.16f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Alert", color = Danger, fontWeight = FontWeight.Bold)
            alerts.forEach { (pid, value) ->
                val d = Units.display(pid, value, units)
                Text("${pid.name}: ${d.text} ${d.unit}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Live and average fuel economy, distance and fuel used since the last reset. */
@Composable
private fun TripCard(state: UiState, onReset: () -> Unit) {
    val trip = state.trip
    val imperial = state.units == UnitSystem.IMPERIAL
    fun economy(l100: Double?): String = when {
        l100 == null -> "—"
        // Coasting burns almost nothing: show a cap instead of an absurd number.
        imperial -> TripComputer.l100kmToMpg(l100).let { if (it > 99.9) "99+" else "%.1f".format(it) }
        else -> if (l100 < 0.1) "0.0" else "%.1f".format(l100.coerceAtMost(99.9))
    }
    val economyUnit = if (imperial) "mpg" else "L/100 km"
    Card {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trip", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("Reset") }
            }
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Now", economy(trip.instantL100km), economyUnit)
                Stat("Average", economy(trip.averageL100km), economyUnit)
                Stat("Distance", "%.1f".format(if (imperial) trip.distanceKm / 1.609344 else trip.distanceKm), if (imperial) "mi" else "km")
                Stat("Fuel", "%.2f".format(if (imperial) trip.fuelLiters / 3.785412 else trip.fuelLiters), if (imperial) "gal" else "L")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (unit.isNotEmpty()) {
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Everything about one sensor: a big graph with min / average / max, plus the
 * customization for it (favorite, gauge, order, alert limits).
 */
@Composable
private fun SensorDetailDialog(pid: Pid, state: UiState, viewModel: ObdViewModel, onDismiss: () -> Unit) {
    val units = state.units
    val value = state.live[pid.code]
    val history = state.history[pid.code].orEmpty()
    val favorite = pid.code in state.favorites
    val gauge = pid.code in state.settings.gauges
    val numeric = pid.label == null
    fun show(v: Double) = Units.display(pid, v, units).let { "${it.text} ${it.unit}".trim() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pid.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(value?.let(::show) ?: "—", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                if (numeric) {
                    Sparkline(history, Modifier.fillMaxWidth().height(140.dp))
                    if (history.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Stat("Min", show(history.min().toDouble()), "")
                            Stat("Average", show(history.average()), "")
                            Stat("Max", show(history.max().toDouble()), "")
                        }
                    }
                }
                val request = pid.request
                Hint(
                    if (request == null) "Standard PID 01 %02X · last ${history.size} readings".format(pid.code)
                    else "Module ${request.header} · command ${request.command}" +
                        (if (request.verified) "" else " · unverified, values may be wrong") + " · last ${history.size} readings",
                )

                HorizontalDivider()
                SwitchRow("Favorite", "Show on the Favorites dashboard", favorite) { viewModel.toggleFavorite(pid.code) }
                if (numeric) {
                    SwitchRow("Big gauge", "Show as a round gauge at the top", gauge) { viewModel.toggleGauge(pid.code) }
                }
                if (favorite) {
                    val index = state.favorites.indexOf(pid.code)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Position ${index + 1} of ${state.favorites.size}", Modifier.weight(1f))
                        TextButton(onClick = { viewModel.moveFavorite(pid.code, -1) }, enabled = index > 0) { Text("Earlier") }
                        TextButton(onClick = { viewModel.moveFavorite(pid.code, 1) }, enabled = index < state.favorites.size - 1) { Text("Later") }
                    }
                }
                if (numeric) {
                    HorizontalDivider()
                    AlertEditor(pid, state.settings.alerts[pid.code], units) { low, high -> viewModel.setAlert(pid.code, low, high) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Low / high warning limits, typed in the user's units and stored in metric. */
@Composable
private fun AlertEditor(pid: Pid, alert: SensorAlert?, units: UnitSystem, onSave: (Double?, Double?) -> Unit) {
    val unit = Units.display(pid, 0.0, units).unit
    fun initial(v: Double?) = v?.let { Units.display(pid, it, units).text }.orEmpty()
    var low by remember(alert) { mutableStateOf(initial(alert?.low)) }
    var high by remember(alert) { mutableStateOf(initial(alert?.high)) }
    fun parse(text: String) = text.replace(',', '.').toDoubleOrNull()?.let { Units.toMetric(pid, it, units) }
    val lowValue = parse(low)
    val highValue = parse(high)
    val invalid = (low.isNotBlank() && lowValue == null) || (high.isNotBlank() && highValue == null) ||
        (lowValue != null && highValue != null && lowValue >= highValue)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Alert")
        Hint("Warn me when this sensor goes outside these limits.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = low, onValueChange = { low = it }, label = { Text("Below ($unit)") }, singleLine = true,
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = high, onValueChange = { high = it }, label = { Text("Above ($unit)") }, singleLine = true,
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        if (invalid) {
            Text("Enter numbers, with \"below\" lower than \"above\".", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onSave(lowValue, highValue) },
                enabled = !invalid && (lowValue != null || highValue != null),
            ) { Text("Save alert") }
            if (alert != null) TextButton(onClick = { onSave(null, null) }) { Text("Remove") }
        }
    }
}

@Composable
private fun Gauge(pid: Pid, value: Double?, units: UnitSystem, alerting: Boolean) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (alerting) Danger else MaterialTheme.colorScheme.primary
    val shown = value?.let { Units.display(pid, it, units) }
    // Scale from the sensor's minimum (e.g. -64° timing advance), not from zero.
    val low = Units.display(pid, pid.min, units).value.coerceAtMost(0.0)
    val high = Units.gaugeMax(pid, units)
    val fraction = (((shown?.value ?: low) - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
    Card {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(14.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.08f
                val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
                val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
                drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(fill, 135f, 270f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(pid.shortName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    shown?.text ?: "—",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (alerting) Danger else Color.Unspecified,
                )
                Text(Units.display(pid, 0.0, units).unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ValueTile(
    pid: Pid,
    value: Double?,
    units: UnitSystem,
    history: List<Float>,
    compact: Boolean,
    alerting: Boolean,
    favorite: Boolean,
) {
    val colors = if (alerting) CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.16f)) else CardDefaults.cardColors()
    Card(colors = colors) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 14.dp)) {
            Text(
                (if (favorite) "★ " else "") + pid.shortName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val d = value?.let { Units.display(pid, it, units) }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    d?.text ?: "—",
                    style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (alerting) Danger else Color.Unspecified,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!d?.unit.isNullOrEmpty()) {
                    Text(
                        " ${d?.unit}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            if (history.size >= 2 && pid.label == null) {
                Sparkline(history, Modifier.fillMaxWidth().height(if (compact) 22.dp else 30.dp).padding(top = 6.dp), alerting)
            }
        }
    }
}

/** A small trend line of recent samples, scaled to their own min/max. */
@Composable
private fun Sparkline(samples: List<Float>, modifier: Modifier = Modifier, alerting: Boolean = false) {
    val color = if (alerting) Danger else MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val min = samples.min()
        val range = (samples.max() - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (samples.size - 1)
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v - min) / range * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** The adapter answers but the car doesn't: explain, show what the adapter said, and offer a retry. */
@Composable
private fun NotRespondingCard(
    state: UiState,
    connection: ConnectionState.Connected,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val volts = state.batteryVoltage
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("The car isn't answering", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Hint(
            when {
                // ~13.5 V and up means the alternator is charging, i.e. the engine is running.
                volts != null && volts >= 13.3 -> "The adapter is working and reads ${"%.1f".format(volts)} V, so the engine " +
                    "seems to be running — the car should answer. Tap Try again: the app will also try the car's protocol " +
                    "directly, which some adapters need."
                else -> "The adapter is connected, but the car didn't answer. Turn the ignition on (or start the engine), " +
                    "then tap Try again."
            },
        )
        Button(onClick = onRetry, enabled = !state.vehicleRetrying) {
            Text(if (state.vehicleRetrying) "Trying… (up to 40 s)" else "Try again")
        }
        if (state.vehicleRetrying) LinearProgressIndicator(Modifier.fillMaxWidth())
        connection.info.probeResponse?.let { answer ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("What the adapter answered", style = MaterialTheme.typography.labelLarge)
                    Text(answer, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Hint("Adapter: ${connection.info.elmVersion ?: "unknown"}. If Try again doesn't help, send a screenshot of this.")
                }
            }
        }
    }
}
