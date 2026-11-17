package com.obdscanner.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.obdscanner.app.ui.ObdApp
import com.obdscanner.app.ui.theme.ObdScannerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ObdViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ObdScannerTheme {
                ObdApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have paired an adapter in system settings meanwhile.
        if (hasBluetoothPermission()) viewModel.refreshPairedDevices()
    }

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
