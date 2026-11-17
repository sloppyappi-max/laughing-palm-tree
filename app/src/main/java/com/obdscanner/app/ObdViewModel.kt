package com.obdscanner.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.obdscanner.app.transport.AdapterTarget
import com.obdscanner.app.transport.BleElmChannel
import com.obdscanner.app.transport.BleLog
import com.obdscanner.app.transport.Transports
import com.obdscanner.core.AdapterInfo
import com.obdscanner.core.Dtc
import com.obdscanner.core.DtcType
import com.obdscanner.core.ElmChannel
import com.obdscanner.core.ElmTimeoutException
import com.obdscanner.core.MonitorStatus
import com.obdscanner.core.ObdClient
import com.obdscanner.core.Pid
import com.obdscanner.core.Pids
import com.obdscanner.core.SimulatedElmChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val target: AdapterTarget, val step: String) : ConnectionState
    data class Connected(val target: AdapterTarget, val info: AdapterInfo) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class PairedDevice(val name: String, val address: String)

data class BleDevice(val name: String, val address: String, val rssi: Int)

data class CodesState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val monitor: MonitorStatus? = null,
    val codes: List<Dtc> = emptyList(),
    val message: String? = null,
)

data class UiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val supportedPids: Set<Int> = emptySet(),
    val live: Map<Int, Double> = emptyMap(),
    val codes: CodesState = CodesState(),
    val vin: String? = null,
    val vinLoading: Boolean = false,
    val batteryVoltage: Double? = null,
    val terminal: List<String> = emptyList(),
    val bleDevices: List<BleDevice> = emptyList(),
    val bleScanning: Boolean = false,
    val bleMessage: String? = null,
    /** Bluetooth LE link report shown after a failed connection, for investigating unknown adapters. */
    val diagnostics: String? = null,
)

class ObdViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val bluetooth: BluetoothAdapter? =
        app.getSystemService(BluetoothManager::class.java)?.adapter

    private var channel: ElmChannel? = null
    private var client: ObdClient? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var liveActive = false

    /** Call once Bluetooth permission is granted (or on Android < 12). */
    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        val adapter = bluetooth
        if (adapter == null) {
            _state.update { it.copy(bluetoothAvailable = false) }
            return
        }
        val devices = runCatching {
            adapter.bondedDevices.orEmpty()
                .map { PairedDevice(it.name ?: it.address, it.address) }
                // Most adapters are called "OBDII", "OBD2", "Vgate", "V-LINK", "OBDLink"…; show them first.
                .sortedByDescending { d -> listOf("OBD", "ELM", "VLINK", "V-LINK", "VGATE").any { d.name.uppercase().contains(it) } }
        }.getOrDefault(emptyList())
        _state.update { it.copy(bluetoothAvailable = true, bluetoothEnabled = adapter.isEnabled, pairedDevices = devices) }
    }

    fun connectBluetooth(device: PairedDevice) {
        val adapter = bluetooth ?: return
        val remote = adapter.getRemoteDevice(device.address)
        connect(AdapterTarget.Bluetooth(remote, device.name)) { Transports.bluetooth(remote) }
    }

    private var scanCallback: ScanCallback? = null
    private var scanTimeout: Job? = null

    /** Looks for Bluetooth LE adapters for 10 seconds. Needs BLUETOOTH_SCAN (or location on Android 11 and lower). */
    @SuppressLint("MissingPermission")
    fun scanBle() {
        val scanner = bluetooth?.bluetoothLeScanner
        if (scanner == null) {
            _state.update { it.copy(bleMessage = "Turn Bluetooth on to search for devices.") }
            return
        }
        stopBleScan()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName
                    ?: runCatching { result.device.name }.getOrNull()
                    ?: return // unnamed devices are almost never OBD adapters
                val device = BleDevice(name, result.device.address, result.rssi)
                _state.update { s ->
                    val list = s.bleDevices.filterNot { it.address == device.address } + device
                    s.copy(bleDevices = list.sortedByDescending { it.rssi })
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _state.update { it.copy(bleScanning = false, bleMessage = "Bluetooth scan failed (code $errorCode).") }
            }
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        } catch (e: SecurityException) {
            _state.update { it.copy(bleMessage = "Bluetooth permission was denied.") }
            return
        }
        scanCallback = callback
        _state.update { it.copy(bleScanning = true, bleDevices = emptyList(), bleMessage = null) }
        scanTimeout = viewModelScope.launch {
            delay(10_000)
            stopBleScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        scanTimeout?.cancel()
        scanCallback?.let { cb -> runCatching { bluetooth?.bluetoothLeScanner?.stopScan(cb) } }
        scanCallback = null
        _state.update { it.copy(bleScanning = false) }
    }

    fun connectBle(device: BleDevice) {
        val adapter = bluetooth ?: return
        stopBleScan()
        val remote = adapter.getRemoteDevice(device.address)
        val log = BleLog()
        connect(AdapterTarget.Ble(remote, device.name), diagnostics = log::text) {
            BleElmChannel.connect(getApplication<Application>(), remote, log)
        }
    }

    fun connectWifi(host: String, port: Int) {
        val target = AdapterTarget.Wifi(host, port)
        connect(target) { Transports.wifi(target) }
    }

    fun connectDemo() = connect(AdapterTarget.Demo) { SimulatedElmChannel() }

    private fun connect(target: AdapterTarget, diagnostics: (() -> String)? = null, open: suspend () -> ElmChannel) {
        disconnect()
        connectJob = viewModelScope.launch {
            try {
                _state.update { it.copy(connection = ConnectionState.Connecting(target, "Opening connection…")) }
                val ch = open()
                channel = ch
                val obd = ObdClient(ch)
                _state.update { it.copy(connection = ConnectionState.Connecting(target, "Initializing adapter…")) }
                val info = obd.initialize()
                client = obd
                val supported = if (info.vehicleResponding) obd.supportedPids() else emptySet()
                _state.update {
                    it.copy(
                        connection = ConnectionState.Connected(target, info),
                        supportedPids = supported,
                        batteryVoltage = info.batteryVoltage,
                    )
                }
                if (liveActive) startPolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeChannel()
                _state.update {
                    it.copy(
                        connection = ConnectionState.Failed(e.message ?: e.javaClass.simpleName),
                        diagnostics = diagnostics?.invoke(),
                    )
                }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        pollJob?.cancel()
        closeChannel()
        _state.update {
            UiState(
                pairedDevices = it.pairedDevices,
                bluetoothAvailable = it.bluetoothAvailable,
                bluetoothEnabled = it.bluetoothEnabled,
                bleDevices = it.bleDevices,
            )
        }
    }

    private fun closeChannel() {
        runCatching { channel?.close() }
        channel = null
        client = null
    }

    private fun lostConnection(e: Exception) {
        pollJob?.cancel()
        closeChannel()
        _state.update { it.copy(connection = ConnectionState.Failed("Connection lost: ${e.message}")) }
    }

    /** The dashboard calls this when it becomes visible / hidden, so we only poll while someone is looking. */
    fun setLiveDataActive(active: Boolean) {
        liveActive = active
        if (active) startPolling() else pollJob?.cancel()
    }

    /** PIDs shown on the dashboard: everything we know how to decode that the car supports. */
    fun dashboardPids(state: UiState): List<Pid> =
        Pids.ALL.filter { it.code in state.supportedPids }

    private fun startPolling() {
        val obd = client ?: return
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            val all = dashboardPids(_state.value)
            if (all.isEmpty()) return@launch
            // Refresh fast-changing values every round, and one slower value per round.
            val fastCodes = setOf(Pids.RPM.code, Pids.SPEED.code, Pids.THROTTLE.code, Pids.ENGINE_LOAD.code)
            val fast = all.filter { it.code in fastCodes }
            val slow = all.filterNot { it.code in fastCodes }
            var slowIndex = 0
            var timeouts = 0
            while (isActive) {
                val round = fast + listOfNotNull(slow.getOrNull(slowIndex))
                if (slow.isNotEmpty()) slowIndex = (slowIndex + 1) % slow.size
                for (pid in round) {
                    try {
                        val value = obd.read(pid)
                        timeouts = 0
                        if (value != null) _state.update { it.copy(live = it.live + (pid.code to value)) }
                    } catch (e: ElmTimeoutException) {
                        if (++timeouts >= 3) return@launch lostConnection(e)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@launch lostConnection(e)
                    }
                }
                if (fast.isEmpty() && slow.size <= 1) delay(500)
            }
        }
    }

    fun readCodes() {
        val obd = client ?: return
        viewModelScope.launch {
            _state.update { it.copy(codes = it.codes.copy(loading = true, message = null)) }
            try {
                val monitor = obd.monitorStatus()
                val codes = DtcType.entries.flatMap { obd.readDtcs(it) }
                _state.update { it.copy(codes = CodesState(loaded = true, monitor = monitor, codes = codes)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(codes = it.codes.copy(loading = false, message = "Could not read codes: ${e.message}")) }
            }
        }
    }

    fun clearCodes() {
        val obd = client ?: return
        viewModelScope.launch {
            _state.update { it.copy(codes = it.codes.copy(loading = true, message = null)) }
            val ok = runCatching { obd.clearDtcs() }.getOrDefault(false)
            _state.update {
                it.copy(
                    codes = it.codes.copy(
                        loading = false,
                        message = if (ok) "Codes cleared." else "The car refused to clear codes. Turn the engine off (ignition on) and try again.",
                    ),
                )
            }
            if (ok) readCodes()
        }
    }

    fun readVehicleInfo() {
        val obd = client ?: return
        viewModelScope.launch {
            _state.update { it.copy(vinLoading = true) }
            val vin = runCatching { obd.readVin() }.getOrNull()
            val voltage = runCatching { obd.batteryVoltage() }.getOrNull()
            _state.update { it.copy(vin = vin, vinLoading = false, batteryVoltage = voltage ?: it.batteryVoltage) }
        }
    }

    /** Raw command console for power users. */
    fun sendRaw(command: String) {
        val obd = client ?: return
        val cmd = command.trim().uppercase()
        if (cmd.isEmpty()) return
        viewModelScope.launch {
            val reply = runCatching { obd.raw(cmd).joinToString("\n").ifEmpty { "(empty)" } }
                .getOrElse { "Error: ${it.message}" }
            _state.update { it.copy(terminal = (it.terminal + "> $cmd" + reply).takeLast(200)) }
        }
    }

    override fun onCleared() {
        stopBleScan()
        closeChannel()
    }
}
