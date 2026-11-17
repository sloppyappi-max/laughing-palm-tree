package com.obdscanner.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.UiState
import com.obdscanner.app.ui.theme.Danger
import com.obdscanner.app.ui.theme.Ok
import com.obdscanner.core.Dtc
import com.obdscanner.core.DtcType

@Composable
fun CodesScreen(state: UiState, viewModel: ObdViewModel, modifier: Modifier = Modifier) {
    val codes = state.codes
    var confirmClear by remember { mutableStateOf(false) }

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
                Column(Modifier.padding(16.dp)) {
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
                    if (monitor != null) {
                        Text("${monitor.dtcCount} confirmed code(s) reported by the car", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            Row {
                Button(onClick = viewModel::readCodes, enabled = !codes.loading) { Text("Read codes") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { confirmClear = true }, enabled = !codes.loading && codes.loaded) { Text("Clear codes") }
            }
        }

        if (codes.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        codes.message?.let { msg -> item { Hint(msg) } }

        if (codes.loaded && codes.codes.isEmpty()) {
            item {
                Text("No trouble codes found.", color = Ok, style = MaterialTheme.typography.titleMedium)
            }
        }

        for (type in DtcType.entries) {
            val ofType = codes.codes.filter { it.type == type }
            if (ofType.isEmpty()) continue
            item(key = "h-$type") { SectionTitle("${type.label} (${ofType.size})") }
            items(ofType, key = { "${it.type}-${it.code}" }) { DtcCard(it) }
        }

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
private fun DtcCard(dtc: Dtc) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(dtc.code, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(dtc.description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
