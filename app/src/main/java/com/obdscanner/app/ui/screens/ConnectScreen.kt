package com.obdscanner.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.SavedAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.obdscanner.app.UiState

// BLUETOOTH_CONNECT is only requested when hasPermission is false, which only happens on Android 12+.
@SuppressLint("InlinedApi")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(state: UiState, viewModel: ObdViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    fun granted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(granted()) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasPermission = ok
        permissionDenied = !ok
        if (ok) viewModel.refreshPairedDevices()
    }
    LaunchedEffect(hasPermission) { if (hasPermission) viewModel.refreshPairedDevices() }
    // The permission may have been granted in system settings while the app was in the background.
    LifecycleResumeEffect(Unit) {
        hasPermission = granted()
        onPauseOrDispose { }
    }

    val connecting = state.connection is ConnectionState.Connecting

    var showBle by rememberSaveable { mutableStateOf(false) }
    var showWifi by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OBD Scanner") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.savedAdapter == null && !connecting) item { GettingStartedCard() }

            when (val c = state.connection) {
                is ConnectionState.Connecting -> item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.target.label, style = MaterialTheme.typography.titleMedium)
                                Text(c.step, style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(onClick = viewModel::disconnect) { Text("Cancel") }
                        }
                    }
                }
                is ConnectionState.Failed -> {
                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        ) {
                            Text(c.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    state.diagnostics?.let { report -> item { DiagnosticsCard(report) } }
                }
                else -> Unit
            }

            state.savedAdapter?.let { saved ->
                if (!connecting) item {
                    Button(onClick = {
                        // Bluetooth adapters need the permission; Wi-Fi ones don't.
                        if (saved.kind == SavedAdapter.Kind.WIFI || hasPermission) viewModel.reconnect()
                        else permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reconnect to ${saved.name}")
                    }
                }
            }

            item { SectionTitle("Bluetooth adapter") }
            when {
                !state.bluetoothAvailable -> item { Hint("This device has no Bluetooth.") }
                !hasPermission -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hint("Allow access to nearby devices so the app can talk to your paired OBD-II adapter.")
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                            Text("Allow Bluetooth access")
                        }
                        if (permissionDenied) PermissionDeniedHint()
                    }
                }
                !state.bluetoothEnabled -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hint("Bluetooth is turned off.")
                        OutlinedButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) }) {
                            Text("Open Bluetooth settings")
                        }
                    }
                }
                else -> {
                    if (state.pairedDevices.isEmpty()) {
                        item { Hint("No paired devices. Plug the adapter into the car, turn the ignition on, and pair it in Bluetooth settings (PIN is usually 1234 or 0000).") }
                    }
                    items(state.pairedDevices, key = { it.address }) { device ->
                        Card(Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(device.name) },
                                supportingContent = { Text(device.address) },
                                modifier = Modifier.clickable(enabled = !connecting) { viewModel.connectBluetooth(device) },
                            )
                        }
                    }
                    item {
                        TextButton(onClick = { context.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) }) {
                            Text("Pair a new adapter…")
                        }
                    }
                }
            }

            item {
                ExpandableSection(
                    title = "Bluetooth LE adapter",
                    subtitle = "Adapters that don't appear in Bluetooth settings",
                    expanded = showBle,
                    onToggle = { showBle = !showBle },
                ) { BleSection(state, viewModel, enabled = !connecting) }
            }
            item {
                ExpandableSection(
                    title = "Wi-Fi adapter",
                    subtitle = "Adapters with their own Wi-Fi network",
                    expanded = showWifi,
                    onToggle = { showWifi = !showWifi },
                ) { WifiForm(enabled = !connecting, onConnect = viewModel::connectWifi) }
            }

            item {
                OutlinedButton(onClick = viewModel::connectDemo, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
                    Text("No adapter? Try demo mode")
                }
            }
        }
    }
}

/** First-run explanation of what's needed. */
@Composable
private fun GettingStartedCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Get started", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            listOf(
                "Plug an ELM327 OBD-II adapter into the port under the dashboard (driver's side).",
                "Turn the ignition on, or start the engine.",
                "Pick your adapter below. The first connection can take up to 15 seconds.",
            ).forEachIndexed { i, step ->
                Text("${i + 1}.  $step", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Hint(subtitle)
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
        }
    }
}

@Composable
private fun WifiForm(enabled: Boolean, onConnect: (String, Int) -> Unit) {
    var host by rememberSaveable { mutableStateOf("192.168.0.10") }
    var port by rememberSaveable { mutableStateOf("35000") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint("Join the adapter's Wi-Fi network first (often called \"WiFi_OBDII\").")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host, onValueChange = { host = it.trim() }, label = { Text("Address") },
                singleLine = true, modifier = Modifier.weight(2f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Button(
            onClick = { onConnect(host, port.toIntOrNull() ?: 35000) },
            enabled = enabled && host.isNotBlank(),
        ) { Text("Connect over Wi-Fi") }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val blePermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** Scan for Bluetooth LE adapters (e.g. Innova, Vgate iCar Pro BLE, OBDLink CX) and connect to one. */
@Composable
private fun BleSection(state: UiState, viewModel: ObdViewModel, enabled: Boolean) {
    val context = LocalContext.current
    var showUnnamed by rememberSaveable { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        denied = !results.values.all { it }
        if (!denied) viewModel.scanBle()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint(
            "For adapters that don't show up in Bluetooth settings, like Innova, Vgate iCar Pro BLE and OBDLink CX. " +
                "Plug the adapter in and turn the ignition on first. Close the adapter's own app, and don't pair it " +
                "in Android's Bluetooth settings (remove it there if you did)." +
                (if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " On this Android version, Location must be turned on to search." else ""),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val missing = blePermissions.filter {
                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) viewModel.scanBle() else launcher.launch(missing.toTypedArray())
                },
                enabled = enabled && !state.bleScanning && state.bluetoothAvailable,
            ) { Text(if (state.bleScanning) "Searching…" else "Search for devices") }
            if (state.bleScanning) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp))
            }
        }
        state.bleMessage?.let { Hint(it) }
        if (denied) PermissionDeniedHint()
        if (!state.bleScanning && state.bleDevices.isEmpty() && state.bleMessage == null) {
            Hint("No devices listed yet.")
        }
        val unnamed = state.bleDevices.count { !it.named }
        if (unnamed > 0) {
            TextButton(onClick = { showUnnamed = !showUnnamed }) {
                Text(if (showUnnamed) "Hide unnamed devices" else "Show $unnamed unnamed device(s)")
            }
        }
        state.bleDevices.filter { it.named || showUnnamed }.forEach { device ->
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(device.name + if (device.looksLikeAdapter) "  · likely OBD adapter" else "") },
                    supportingContent = { Text("${device.address} · signal ${device.rssi} dBm") },
                    modifier = Modifier.clickable(enabled = enabled) { viewModel.connectBle(device) },
                )
            }
        }
    }
}

/** Shows what an unrecognised Bluetooth LE adapter offered and sent back, ready to copy and share. */
@Composable
private fun DiagnosticsCard(report: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Adapter diagnostics", style = MaterialTheme.typography.titleMedium)
            Hint("What the adapter offered and answered while connecting. Copy it and share it to get help with this adapter.")
            SelectionContainer {
                Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                clipboard.setText(AnnotatedString(report))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy report") }
        }
    }
}

/** Shown after a permission was refused: Android stops asking, so offer the app's settings page. */
@Composable
private fun PermissionDeniedHint() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Hint("Permission was refused. If Android no longer asks, allow \"Nearby devices\" (or Location) in the app's settings.")
        TextButton(onClick = {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text("Open app settings") }
    }
}
