package com.obdscanner.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.UiState
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
    // Poll only while this screen is visible and the app is in the foreground.
    LifecycleResumeEffect(Unit) {
        viewModel.setLiveDataActive(true)
        onPauseOrDispose { viewModel.setLiveDataActive(false) }
    }
    // Keep the display on while watching live data.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    if (!connection.info.vehicleResponding) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Hint(
                "The adapter is connected, but the car isn't answering. Turn the ignition on (or start the engine), " +
                    "then disconnect and connect again.",
            )
        }
        return
    }

    val pids = viewModel.dashboardPids(state)
    val gauges = listOf(Pids.RPM, Pids.SPEED).filter { it in pids }
    val tiles = pids - gauges.toSet()
    var detail by remember { mutableStateOf<Pid?>(null) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                    label = { Text("All sensors (${state.supportedPids.count { Pids.byCode(it) != null }})") },
                )
            }
        }
        if (state.trip.available) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "trip") { TripCard(state, onReset = viewModel::resetTrip) }
        }
        items(gauges, key = { "g${it.code}" }) { pid ->
            Box(Modifier.clickable { detail = pid }) { Gauge(pid, state.live[pid.code], state.units) }
        }
        if (pids.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Hint(
                    if (state.showAllSensors) "This car reports no live data the app knows how to show."
                    else "No favorites yet. Open \"All sensors\", tap a sensor and add it to the dashboard.",
                )
            }
        }
        items(tiles, key = { it.code }) { pid ->
            Box(Modifier.clickable { detail = pid }) {
                ValueTile(pid, state.live[pid.code], state.units, state.history[pid.code].orEmpty())
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "hint") {
            Hint("Tap a sensor for a bigger graph, or to add it to / remove it from your favorites.")
        }
    }

    detail?.let { pid ->
        SensorDetailDialog(
            pid = pid,
            value = state.live[pid.code],
            history = state.history[pid.code].orEmpty(),
            units = state.units,
            favorite = pid.code in state.favorites,
            onToggleFavorite = { viewModel.toggleFavorite(pid.code) },
            onDismiss = { detail = null },
        )
    }
}

/** Live and average fuel economy, distance and fuel used since the last reset. */
@Composable
private fun TripCard(state: UiState, onReset: () -> Unit) {
    val trip = state.trip
    val imperial = state.units == UnitSystem.IMPERIAL
    fun economy(l100: Double?): String = when {
        l100 == null -> "—"
        imperial -> "%.1f".format(TripComputer.l100kmToMpg(l100))
        else -> "%.1f".format(l100)
    }
    val economyUnit = if (imperial) "mpg" else "L/100 km"
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trip", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("Reset") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TripStat("Now", economy(trip.instantL100km), economyUnit)
                TripStat("Average", economy(trip.averageL100km), economyUnit)
                TripStat(
                    "Distance",
                    "%.1f".format(if (imperial) trip.distanceKm / 1.609344 else trip.distanceKm),
                    if (imperial) "mi" else "km",
                )
                TripStat(
                    "Fuel",
                    "%.2f".format(if (imperial) trip.fuelLiters / 3.785412 else trip.fuelLiters),
                    if (imperial) "gal" else "L",
                )
            }
        }
    }
}

@Composable
private fun TripStat(label: String, value: String, unit: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A bigger graph of one sensor with min / average / max of the recent samples. */
@Composable
private fun SensorDetailDialog(
    pid: Pid,
    value: Double?,
    history: List<Float>,
    units: UnitSystem,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
) {
    fun show(v: Double) = Units.display(pid, v, units).let { "${it.text} ${it.unit}".trim() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pid.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(value?.let(::show) ?: "—", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                if (pid.label == null) {
                    Sparkline(history, Modifier.fillMaxWidth().height(140.dp))
                    if (history.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TripStat("Min", show(history.min().toDouble()), "")
                            TripStat("Average", show(history.average()), "")
                            TripStat("Max", show(history.max().toDouble()), "")
                        }
                    }
                }
                Hint("PID 01 %02X · last ${history.size} readings".format(pid.code))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = onToggleFavorite) {
                Text(if (favorite) "Remove from favorites" else "Add to favorites")
            }
        },
    )
}

@Composable
private fun Gauge(pid: Pid, value: Double?, units: UnitSystem) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val shown = value?.let { Units.display(pid, it, units) }
    val fraction = ((shown?.value ?: 0.0) / Units.gaugeMax(pid, units)).coerceIn(0.0, 1.0).toFloat()
    Card {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.09f
                val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
                val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
                drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(fill, 135f, 270f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    shown?.text ?: "—",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(Units.display(pid, 0.0, units).unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ValueTile(pid: Pid, value: Double?, units: UnitSystem, history: List<Float>) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(pid.shortName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value?.let { Units.display(pid, it, units).let { d -> "${d.text} ${d.unit}" } } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Sparkline(history, Modifier.fillMaxWidth().height(32.dp).padding(top = 6.dp))
        }
    }
}

/** A small trend line of recent samples, scaled to their own min/max. */
@Composable
private fun Sparkline(samples: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
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
