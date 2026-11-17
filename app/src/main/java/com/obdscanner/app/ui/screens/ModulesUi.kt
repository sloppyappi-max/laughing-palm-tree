package com.obdscanner.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.UiState
import com.obdscanner.app.ui.theme.Caution
import com.obdscanner.app.ui.theme.Danger
import com.obdscanner.app.ui.theme.Ok
import com.obdscanner.core.ModuleReport

/** All-system scan: every module on the main CAN bus and its trouble codes. Experimental. */
@Composable
fun SystemScanCard(state: UiState, viewModel: ObdViewModel) {
    val scan = state.scan
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("All-system scan", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("EXPERIMENTAL", style = MaterialTheme.typography.labelSmall, color = Caution, fontWeight = FontWeight.Bold)
            }
            if (!state.moduleAccess) {
                Hint("Needs a car with 11-bit CAN (most 2008+ US vehicles). This car's protocol isn't supported.")
                return@Column
            }
            Hint(
                "Asks every module on the main network (engine, transmission, ABS, airbag, …) for its trouble codes. " +
                    "Read-only. Modules on GM's single-wire network (some body modules) can't be reached with an ELM327.",
            )
            Row {
                Button(onClick = { viewModel.scanModules(full = false) }, enabled = !scan.running) { Text("Quick scan") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { viewModel.scanModules(full = true) }, enabled = !scan.running) { Text("Full scan") }
            }
            if (scan.running) {
                LinearProgressIndicator(progress = { scan.progress }, modifier = Modifier.fillMaxWidth())
                Hint(if (scan.full) "Full scan checks ~250 addresses and takes about a minute." else "Checking modules…")
            }
            scan.result?.let { result ->
                if (result.reports.isEmpty()) Hint("No modules answered.")
                result.reports.forEach { report ->
                    HorizontalDivider()
                    ModuleRow(report)
                }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "OBD Scanner module scan log")
                        putExtra(Intent.EXTRA_TEXT, result.log)
                    }
                    context.startActivity(Intent.createChooser(send, "Share scan log"))
                }) { Text("Share scan log") }
            }
        }
    }
}

@Composable
private fun ModuleRow(report: ModuleReport) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(report.module.name, fontWeight = FontWeight.SemiBold)
                Hint("Address ${report.module.requestHex} → ${report.module.responseHex}")
            }
            Text(
                when {
                    report.problem != null -> "?"
                    report.dtcs.isEmpty() -> "OK"
                    else -> "${report.dtcs.size} code(s)"
                },
                color = when {
                    report.problem != null -> Caution
                    report.dtcs.isEmpty() -> Ok
                    else -> Danger
                },
                fontWeight = FontWeight.Bold,
            )
        }
        report.problem?.let { Hint(it) }
        report.dtcs.forEach { dtc ->
            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    dtc.fullCode + (if (dtc.activeNow) "  · active now" else "") + "  · ${dtc.protocol.label}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Hint(dtc.description)
            }
        }
    }
}

/** Advanced: ask a module for a range of data IDs and list the ones that answer. */
@Composable
fun IdExplorerCard(state: UiState, viewModel: ObdViewModel) {
    val explorer = state.explorer
    var header by rememberSaveable { mutableStateOf(explorer.header) }
    var from by rememberSaveable { mutableStateOf("1900") }
    var to by rememberSaveable { mutableStateOf("19FF") }
    val fromId = from.toIntOrNull(16)
    val toId = to.toIntOrNull(16)
    val valid = header.matches(Regex("[0-9A-Fa-f]{3}")) && fromId != null && toId != null && toId >= fromId && toId - fromId <= 0x3FF

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ID explorer", style = MaterialTheme.typography.titleMedium)
            Hint(
                "Asks one module for every data ID in a range (read-only, service 22) and lists the IDs that answer, " +
                    "with their raw bytes. Use it to find manufacturer values, then add them as custom sensors in Settings.",
            )
            if (!state.moduleAccess) {
                Hint("Needs an 11-bit CAN car.")
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("Module", header) { v: String -> header = v.take(3).uppercase() },
                    Triple("From ID", from) { v: String -> from = v.take(4).uppercase() },
                    Triple("To ID", to) { v: String -> to = v.take(4).uppercase() },
                ).forEach { (label, value, onChange) ->
                    OutlinedTextField(
                        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                }
            }
            Hint("Up to 1024 IDs per run, about 0.1 s each. e.g. module 7E2 (transmission), IDs 1900–19FF.")
            Button(onClick = { viewModel.exploreDids(header, fromId!!, toId!!) }, enabled = valid && !explorer.running) {
                Text("Explore")
            }
            if (explorer.running) LinearProgressIndicator(progress = { explorer.progress }, modifier = Modifier.fillMaxWidth())
            explorer.results?.let { results ->
                if (results.isEmpty()) Hint("No IDs answered in that range.")
                results.forEach { (did, bytes) ->
                    Text(
                        "%04X: %s".format(did, bytes.joinToString(" ") { "%02X".format(it) }),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
