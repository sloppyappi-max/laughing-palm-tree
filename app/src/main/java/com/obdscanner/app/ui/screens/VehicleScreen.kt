package com.obdscanner.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.UiState
import com.obdscanner.core.UnitSystem
import com.obdscanner.core.VehicleIdentity

@Composable
fun VehicleScreen(
    state: UiState,
    viewModel: ObdViewModel,
    connection: ConnectionState.Connected,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { if (state.vehicleInfo == null) viewModel.readVehicleInfo() }
    val info = connection.info

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VehicleIdentity.fromVin(state.vin).label?.let { InfoRow("Vehicle", it) }
                    InfoRow("VIN", if (state.vinLoading) "Reading…" else state.vin ?: "Not reported")
                    InfoRow("Protocol", info.protocol ?: "—")
                    InfoRow("Battery", state.batteryVoltage?.let { "%.1f V".format(it) } ?: "—")
                    InfoRow("Adapter", info.elmVersion ?: "Unknown")
                    InfoRow("Connected via", connection.target.label)
                    InfoRow("Supported PIDs", state.supportedPids.size.toString())
                }
            }
        }

        val v = state.vehicleInfo
        val hasModuleInfo = info.ecus.isNotEmpty() ||
            (v != null && (v.ecuNames.isNotEmpty() || v.calibrationIds.isNotEmpty() || v.cvns.isNotEmpty()))
        if (hasModuleInfo) {
            item { SectionTitle("Modules & calibration") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        info.ecus.forEach { ecu -> InfoRow(ecu.name, ecu.header) }
                        state.vehicleInfo?.let { v ->
                            v.ecuNames.forEach { InfoRow("Module name", it) }
                            v.calibrationIds.forEach { InfoRow("Calibration ID", it) }
                            v.cvns.forEach { InfoRow("CVN", it) }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = viewModel::readVehicleInfo) { Text("Refresh") }
                Button(onClick = viewModel::disconnect) { Text("Disconnect") }
            }
        }

        item { SectionTitle("Advanced") }
        item { AdapterTestCard(state, onRun = viewModel::runAdapterTest) }
        item { IdExplorerCard(state, viewModel) }

        item { SectionTitle("Terminal (advanced)") }
        item { Hint("Send raw ELM327 or OBD commands, e.g. ATRV, ATDP, 010C, 0902.") }
        item { Terminal(onSend = viewModel::sendRaw) }
        items(state.terminal.reversed()) { line ->
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Long values (calibration IDs) wrap on the right instead of squeezing the label.
        Text(value, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Terminal(onSend: (String) -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(command)
        command = ""
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a command, e.g. 010C") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        Button(onClick = send, enabled = command.isNotBlank()) { Text("Send") }
    }
}
