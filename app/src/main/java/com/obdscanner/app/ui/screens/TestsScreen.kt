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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.UiState
import com.obdscanner.app.ui.theme.Danger
import com.obdscanner.app.ui.theme.Ok
import com.obdscanner.core.MonitorTest

/** Mode 06: the car's own emissions self-test results, grouped by monitor. */
@Composable
fun TestsScreen(state: UiState, viewModel: ObdViewModel, modifier: Modifier = Modifier) {
    val tests = state.monitorTests
    LaunchedEffect(Unit) { if (!tests.loaded && !tests.loading) viewModel.runMonitorTests() }

    val groups = tests.tests.groupBy { it.mid }.toSortedMap()
    val failed = tests.tests.count { !it.passed }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Hint(
                "Results of the self-tests the car runs on its emissions parts while you drive. " +
                    "A failing test can point at a problem before it sets a code.",
            )
        }
        item {
            Button(onClick = viewModel::runMonitorTests, enabled = !tests.loading) { Text("Run / refresh") }
        }
        if (tests.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        tests.message?.let { item { Hint(it) } }

        if (tests.loaded && tests.tests.isNotEmpty()) {
            item {
                Text(
                    if (failed == 0) "All ${tests.tests.size} tests passed" else "$failed of ${tests.tests.size} tests failed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (failed == 0) Ok else Danger,
                )
            }
        }

        items(groups.entries.toList(), key = { it.key }) { (_, list) -> MonitorCard(list) }
    }
}

@Composable
private fun MonitorCard(tests: List<MonitorTest>) {
    val allPassed = tests.all { it.passed }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tests.first().monitorName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (allPassed) "PASS" else "FAIL",
                    fontWeight = FontWeight.Bold,
                    color = if (allPassed) Ok else Danger,
                )
            }
            tests.forEach { t ->
                Column {
                    Text(t.testName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${fmt(t.value)} ${t.unit}   (limits ${fmt(t.min)} – ${fmt(t.max)})".trim(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (t.passed) MaterialTheme.colorScheme.onSurfaceVariant else Danger,
                    )
                }
            }
        }
    }
}

private fun fmt(v: Double): String = when {
    v == Math.floor(v) && kotlin.math.abs(v) < 1e7 -> "%.0f".format(v)
    kotlin.math.abs(v) >= 10 -> "%.1f".format(v)
    else -> "%.3f".format(v)
}
