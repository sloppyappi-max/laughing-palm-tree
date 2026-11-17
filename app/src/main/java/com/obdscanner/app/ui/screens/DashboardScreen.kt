package com.obdscanner.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(gauges, key = { "g${it.code}" }) { pid -> Gauge(pid, state.live[pid.code]) }
        if (pids.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Hint("This car reports no live data the app knows how to show.") }
        }
        items(tiles, key = { it.code }) { pid -> ValueTile(pid, state.live[pid.code]) }
    }
}

@Composable
private fun Gauge(pid: Pid, value: Double?) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    val max = if (pid == Pids.RPM) 7000.0 else 200.0
    val fraction = ((value ?: 0.0) / max).coerceIn(0.0, 1.0).toFloat()
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
                    value?.let(pid::format) ?: "—",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(pid.unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ValueTile(pid: Pid, value: Double?) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(pid.shortName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value?.let { "${pid.format(it)} ${pid.unit}" } ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
