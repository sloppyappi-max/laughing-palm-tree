package com.obdscanner.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import com.obdscanner.app.ui.theme.Caution
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.UiState
import com.obdscanner.app.ui.theme.Danger
import com.obdscanner.app.ui.theme.Ok
import com.obdscanner.core.Dtc
import com.obdscanner.core.DtcType
import com.obdscanner.core.FreezeFrame
import com.obdscanner.core.MonitorStatus
import com.obdscanner.core.UnitSystem
import com.obdscanner.core.Units
import com.obdscanner.core.VehicleIdentity

@Composable
fun CodesScreen(state: UiState, viewModel: ObdViewModel, modifier: Modifier = Modifier) {
    val codes = state.codes
    var confirmClear by remember { mutableStateOf(false) }
    val vehicle = VehicleIdentity.fromVin(state.vin).label

    LaunchedEffect(Unit) { if (!codes.loaded && !codes.loading) viewModel.readCodes() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val monitor = codes.monitor
            val milOn = monitor?.milOn == true
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (milOn) Danger.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            monitor == null -> "Check engine light: unknown"
                            milOn -> "Check engine light is ON"
                            else -> "Check engine light is off"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (milOn) Danger else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        when {
                            codes.loading -> "Reading codes…"
                            codes.loaded && codes.codes.isEmpty() -> "No trouble codes found."
                            codes.loaded -> "${codes.codes.size} code(s) found: " +
                                DtcType.entries.mapNotNull { t ->
                                    codes.codes.count { it.type == t }.takeIf { it > 0 }?.let { "$it ${t.label.lowercase()}" }
                                }.joinToString(", ")
                            else -> "Tap Read codes to check."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (codes.loaded && codes.codes.isEmpty()) Ok else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        Button(onClick = viewModel::readCodes, enabled = !codes.loading) { Text("Read codes") }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = { confirmClear = true }, enabled = !codes.loading && codes.loaded) { Text("Clear codes") }
                    }
                }
            }
        }

        if (codes.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        codes.message?.let { msg -> item { Hint(msg) } }

        for (type in DtcType.entries) {
            val ofType = codes.codes.filter { it.type == type }
            if (ofType.isEmpty()) continue
            item(key = "h-$type") { SectionTitle("${type.label} (${ofType.size})") }
            items(ofType, key = { "${it.type}-${it.code}" }) { DtcCard(it, vehicle) }
        }

        codes.freezeFrame?.let { frame -> item { FreezeFrameCard(frame, state.units) } }

        codes.monitor?.takeIf { it.monitors.isNotEmpty() }?.let { monitor -> item { ReadinessCard(monitor) } }

        item {
            Hint(
                "Stored codes turned the check-engine light on. Pending codes were seen once and are being watched. " +
                    "Permanent codes can only be cleared by the car itself after the fault is fixed.",
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear trouble codes?") },
            text = {
                Text(
                    "This turns off the check-engine light and also resets the car's emission readiness monitors, " +
                        "which can make it fail an inspection until it has been driven for a while. " +
                        "Do this with the engine off and the ignition on.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; viewModel.clearCodes() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DtcCard(dtc: Dtc, vehicle: String?) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dtc.code,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                val (tagColor, tagText) = when (dtc.type) {
                    DtcType.STORED -> Danger to "Stored"
                    DtcType.PENDING -> Caution to "Pending"
                    DtcType.PERMANENT -> MaterialTheme.colorScheme.tertiary to "Permanent"
                }
                Text(
                    tagText,
                    style = MaterialTheme.typography.labelMedium,
                    color = tagColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(tagColor.copy(alpha = 0.14f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Text(dtc.description, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                val query = listOfNotNull(dtc.code, vehicle).joinToString(" ")
                val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }) { Text(if (vehicle != null) "Search online for $vehicle" else "Search online") }
        }
    }
}

/** Emissions readiness: what a smog-check station looks at. */
@Composable
private fun ReadinessCard(monitor: MonitorStatus) {
    val passes = monitor.likelyPassesInspection
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (passes) "Smog check: likely ready" else "Smog check: not ready",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (passes) Ok else Danger,
            )
            val incomplete = monitor.incompleteMonitors.size
            Hint(
                when {
                    monitor.milOn -> "The check-engine light is on, which fails an inspection in most states."
                    incomplete == 0 -> "All readiness monitors have finished their self-tests."
                    passes -> "One monitor hasn't finished; most states allow one (two for 1996–2000 cars)."
                    else -> "$incomplete monitors haven't finished. Drive normally for a few days (highway and city) and check again."
                },
            )
            monitor.monitors.forEach { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(m.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (m.complete) "Ready" else "Not ready",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (m.complete) Ok else Danger,
                    )
                }
            }
        }
    }
}

/** Engine conditions saved when the code was set: often the best clue to its cause. */
@Composable
private fun FreezeFrameCard(frame: FreezeFrame, units: UnitSystem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Freeze frame — ${frame.dtc}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Hint("What the engine was doing when this code was set.")
            frame.values.forEach { (pid, value) ->
                val shown = Units.display(pid, value, units)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(pid.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${shown.text} ${shown.unit}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
