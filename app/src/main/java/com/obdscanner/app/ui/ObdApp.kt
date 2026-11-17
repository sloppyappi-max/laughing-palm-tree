package com.obdscanner.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obdscanner.app.ConnectionState
import com.obdscanner.app.ObdViewModel
import com.obdscanner.app.ui.screens.CodesScreen
import com.obdscanner.app.ui.screens.ConnectScreen
import com.obdscanner.app.ui.screens.DashboardScreen
import com.obdscanner.app.ui.screens.VehicleScreen

private enum class Tab(val title: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Home),
    Codes("Trouble codes", Icons.Filled.Warning),
    Vehicle("Vehicle", Icons.Filled.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObdApp(viewModel: ObdViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connection = state.connection

    if (connection !is ConnectionState.Connected) {
        ConnectScreen(state, viewModel)
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Dashboard) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(tab.title) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.Dashboard -> DashboardScreen(state, viewModel, connection, modifier)
            Tab.Codes -> CodesScreen(state, viewModel, modifier)
            Tab.Vehicle -> VehicleScreen(state, viewModel, connection, modifier)
        }
    }
}
