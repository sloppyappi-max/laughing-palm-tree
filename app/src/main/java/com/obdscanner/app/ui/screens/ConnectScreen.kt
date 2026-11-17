package com.obdscanner.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(state: UiState, viewModel: ObdViewModel) {
    val context = LocalContext.current
    fun granted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(granted()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasPermission = ok
        if (ok) viewModel.refreshPairedDevices()
    }
    LaunchedEffect(hasPermission) { if (hasPermission) viewModel.refreshPairedDevices() }

    val connecting = state.connection is ConnectionState.Connecting

    Scaffold(topBar = { TopAppBar(title = { Text("OBD Scanner") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                is ConnectionState.Failed -> item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(c.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                else -> Unit
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
                    }
                }
                !state.bluetoothEnabled -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hint("Bluetooth is turned off.")
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
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
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                            Text("Pair a new adapter…")
                        }
                    }
                }
            }

            item { SectionTitle("Wi-Fi adapter") }
            item { WifiForm(enabled = !connecting, onConnect = viewModel::connectWifi) }

            item { SectionTitle("No adapter?") }
            item {
                OutlinedButton(onClick = viewModel::connectDemo, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
                    Text("Try demo mode (simulated car)")
                }
            }
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
