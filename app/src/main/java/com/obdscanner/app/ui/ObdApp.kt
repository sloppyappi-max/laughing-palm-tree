package com.obdscanner.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.UiState
import com.obdscanner.app.ui.screens.CodesScreen
import com.obdscanner.app.ui.screens.ConnectScreen
import com.obdscanner.app.ui.screens.DashboardScreen
import com.obdscanner.app.ui.screens.SettingsScreen
import com.obdscanner.app.ui.screens.TestsScreen
import com.obdscanner.app.ui.screens.VehicleScreen
import com.obdscanner.app.ui.theme.Danger
import com.obdscanner.app.ui.theme.Ok
import java.io.File

private enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    Dashboard("Live", "Live data", Icons.Filled.Home),
    Codes("Codes", "Trouble codes", Icons.Filled.Warning),
    Tests("Tests", "Self-tests", Icons.Filled.Build),
    Vehicle("Vehicle", "Vehicle", Icons.Filled.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObdApp(viewModel: ObdViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    if (settingsOpen) {
        SettingsScreen(state, viewModel, onBack = { settingsOpen = false })
        return
    }

    val connection = state.connection
    if (connection !is ConnectionState.Connected) {
        ConnectScreen(state, viewModel, onOpenSettings = { settingsOpen = true })
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Dashboard) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tab.title)
                        StatusLine(state, connection)
                    }
                },
                actions = {
                    if (tab == Tab.Dashboard && connection.info.vehicleResponding) {
                        RecordingActions(state, viewModel)
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.Dashboard -> DashboardScreen(state, viewModel, connection, modifier)
            Tab.Codes -> CodesScreen(state, viewModel, modifier)
            Tab.Tests -> TestsScreen(state, viewModel, modifier)
            Tab.Vehicle -> VehicleScreen(state, viewModel, connection, modifier)
        }
    }
}

/** "OBDII · 12.6 V · Check engine ON": the essentials, visible on every tab. */
@Composable
private fun StatusLine(state: UiState, connection: ConnectionState.Connected) {
    val monitor = state.codes.monitor
    val text = buildAnnotatedString {
        append(connection.target.label)
        state.batteryVoltage?.let { append(" · %.1f V".format(it)) }
        when {
            !connection.info.vehicleResponding -> append(" · car not responding")
            monitor?.milOn == true -> withStyle(SpanStyle(color = Danger, fontWeight = FontWeight.SemiBold)) {
                append(" · Check engine ON")
            }
            monitor != null -> withStyle(SpanStyle(color = Ok)) { append(" · No warning light") }
        }
        if (state.recording) withStyle(SpanStyle(color = Danger)) { append(" · ● REC") }
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Record / stop live data to CSV, and share the last recording. */
@Composable
private fun RecordingActions(state: UiState, viewModel: ObdViewModel) {
    val context = LocalContext.current
    if (state.recording) {
        TextButton(onClick = viewModel::stopRecording) {
            Text("Stop (${state.recordedRows})", color = Danger)
        }
        return
    }
    state.lastRecording?.takeIf { it.exists() }?.let { file ->
        IconButton(onClick = { shareFile(context, file) }) {
            Icon(Icons.Filled.Share, contentDescription = "Share last recording")
        }
    }
    TextButton(onClick = viewModel::startRecording) { Text("Record") }
}

private fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share OBD log"))
}
