package com.obdscanner.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
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
import com.obdscanner.core.FreezeFrame
import com.obdscanner.core.MonitorTest
import com.obdscanner.core.TripComputer
import com.obdscanner.core.VehicleInfo
import com.obdscanner.core.UnitSystem
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val target: AdapterTarget, val step: String) : ConnectionState
    data class Connected(val target: AdapterTarget, val info: AdapterInfo) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class PairedDevice(val name: String, val address: String)

data class BleDevice(val name: String, val address: String, val rssi: Int)

/** The last adapter that connected successfully, for one-tap reconnect. */
data class SavedAdapter(val kind: Kind, val name: String, val address: String, val port: Int = 0) {
    enum class Kind { BLUETOOTH, BLE, WIFI }
}

data class CodesState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val monitor: MonitorStatus? = null,
    val codes: List<Dtc> = emptyList(),
    val freezeFrame: FreezeFrame? = null,
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
    val units: UnitSystem = UnitSystem.METRIC,
    /** Recent samples per PID (metric), oldest first, for the dashboard graphs. */
    val history: Map<Int, List<Float>> = emptyMap(),
    val recording: Boolean = false,
    val recordedRows: Int = 0,
    val lastRecording: File? = null,
    val savedAdapter: SavedAdapter? = null,
    val favorites: Set<Int> = Pids.DEFAULT_FAVORITES,
    val showAllSensors: Boolean = false,
    val trip: TripState = TripState(),
    val monitorTests: TestsState = TestsState(),
    val vehicleInfo: VehicleInfo? = null,
)

private const val HISTORY_SIZE = 90

data class TripState(
    val available: Boolean = false,
    val instantL100km: Double? = null,
    val averageL100km: Double? = null,
    val distanceKm: Double = 0.0,
    val fuelLiters: Double = 0.0,
)

data class TestsState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val tests: List<MonitorTest> = emptyList(),
    val message: String? = null,
)

class ObdViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(
            units = prefs.getString("units", null)?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() }
                ?: if (Locale.getDefault().country in setOf("US", "LR", "MM")) UnitSystem.IMPERIAL else UnitSystem.METRIC,
            savedAdapter = loadSavedAdapter(),
            favorites = prefs.getStringSet("favorites", null)?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?: Pids.DEFAULT_FAVORITES,
        ),
    )
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
        val saved = SavedAdapter(SavedAdapter.Kind.BLUETOOTH, device.name, device.address)
        connect(AdapterTarget.Bluetooth(remote, device.name), saved) { Transports.bluetooth(remote) }
    }

    fun setUnits(units: UnitSystem) {
        prefs.edit().putString("units", units.name).apply()
        _state.update { it.copy(units = units) }
    }

    /** Connects to the adapter that worked last time. */
    fun reconnect() {
        val saved = _state.value.savedAdapter ?: return
        when (saved.kind) {
            SavedAdapter.Kind.BLUETOOTH -> connectBluetooth(PairedDevice(saved.name, saved.address))
            SavedAdapter.Kind.BLE -> connectBle(BleDevice(saved.name, saved.address, 0))
            SavedAdapter.Kind.WIFI -> connectWifi(saved.address, saved.port)
        }
    }

    private fun loadSavedAdapter(): SavedAdapter? {
        val kind = prefs.getString("adapter_kind", null)?.let { runCatching { SavedAdapter.Kind.valueOf(it) }.getOrNull() }
            ?: return null
        return SavedAdapter(
            kind,
            prefs.getString("adapter_name", null) ?: return null,
            prefs.getString("adapter_address", null) ?: return null,
            prefs.getInt("adapter_port", 0),
        )
    }

    private fun saveAdapter(saved: SavedAdapter) {
        prefs.edit()
            .putString("adapter_kind", saved.kind.name)
            .putString("adapter_name", saved.name)
            .putString("adapter_address", saved.address)
            .putInt("adapter_port", saved.port)
            .apply()
        _state.update { it.copy(savedAdapter = saved) }
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
        val saved = SavedAdapter(SavedAdapter.Kind.BLE, device.name, device.address)
        connect(AdapterTarget.Ble(remote, device.name), saved, diagnostics = log::text) {
            BleElmChannel.connect(getApplication<Application>(), remote, log)
        }
    }

    fun connectWifi(host: String, port: Int) {
        val target = AdapterTarget.Wifi(host, port)
        connect(target, SavedAdapter(SavedAdapter.Kind.WIFI, target.label, host, port)) { Transports.wifi(target) }
    }

    fun connectDemo() = connect(AdapterTarget.Demo, saved = null) { SimulatedElmChannel() }

    private fun connect(
        target: AdapterTarget,
        saved: SavedAdapter?,
        diagnostics: (() -> String)? = null,
        open: suspend () -> ElmChannel,
    ) {
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
                // The VIN tells us the year and make, used to search trouble codes online.
                val vin = if (info.vehicleResponding) runCatching { obd.readVin() }.getOrNull() else null
                _state.update {
                    it.copy(
                        connection = ConnectionState.Connected(target, info),
                        supportedPids = supported,
                        vin = vin,
                        batteryVoltage = info.batteryVoltage,
                    )
                }
                saved?.let(::saveAdapter)
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
        stopRecording()
        closeChannel()
        trip.reset()
        // Forget everything about the car; keep settings and device lists.
        _state.update {
            it.copy(
                connection = ConnectionState.Disconnected,
                supportedPids = emptySet(),
                live = emptyMap(),
                history = emptyMap(),
                codes = CodesState(),
                monitorTests = TestsState(),
                vehicleInfo = null,
                trip = TripState(),
                vin = null,
                vinLoading = false,
                batteryVoltage = null,
                terminal = emptyList(),
                diagnostics = null,
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
        stopRecording()
        closeChannel()
        _state.update { it.copy(connection = ConnectionState.Failed("Connection lost: ${e.message}")) }
    }

    /** The dashboard calls this when it becomes visible / hidden, so we only poll while someone is looking. */
    fun setLiveDataActive(active: Boolean) {
        liveActive = active
        if (active) startPolling() else pollJob?.cancel()
    }

    /** PIDs shown on the dashboard: favorites, or every supported sensor in "All sensors" mode. */
    fun dashboardPids(state: UiState): List<Pid> =
        Pids.ALL.filter { it.code in state.supportedPids && (state.showAllSensors || it.code in state.favorites) }

    /** What to poll: the visible sensors plus whatever the trip computer needs. */
    private fun pollPids(state: UiState): List<Pid> {
        val tripPids = listOf(Pids.SPEED, Pids.MAF, Pids.FUEL_RATE).filter { it.code in state.supportedPids }
        return (dashboardPids(state) + tripPids).distinct()
    }

    fun toggleFavorite(code: Int) {
        _state.update {
            val favorites = if (code in it.favorites) it.favorites - code else it.favorites + code
            prefs.edit().putStringSet("favorites", favorites.map(Int::toString).toSet()).apply()
            it.copy(favorites = favorites)
        }
    }

    fun setShowAllSensors(show: Boolean) = _state.update { it.copy(showAllSensors = show) }

    private val trip = TripComputer()

    fun resetTrip() {
        trip.reset()
        publishTrip()
    }

    private fun publishTrip() {
        val supported = _state.value.supportedPids
        val available = Pids.SPEED.code in supported && (Pids.MAF.code in supported || Pids.FUEL_RATE.code in supported)
        _state.update {
            it.copy(
                trip = TripState(available, trip.instantL100km, trip.averageL100km, trip.distanceKm, trip.fuelLiters),
            )
        }
    }

    /** Mode 06 self-test results. */
    fun runMonitorTests() {
        val obd = client ?: return
        viewModelScope.launch {
            _state.update { it.copy(monitorTests = it.monitorTests.copy(loading = true, message = null)) }
            try {
                val tests = obd.readMonitorTests()
                _state.update {
                    it.copy(
                        monitorTests = TestsState(
                            loaded = true,
                            tests = tests,
                            message = if (tests.isEmpty()) "This car didn't report any self-test results (Mode 06 needs a 2008+ CAN car, or the tests haven't run since codes were cleared)." else null,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(monitorTests = it.monitorTests.copy(loading = false, message = "Could not read test results: ${e.message}")) }
            }
        }
    }

    private fun startPolling() {
        val obd = client ?: return
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            // Refresh fast-changing values every round, and one slower value per round.
            // The list is re-read every round so favorites / "All sensors" apply immediately.
            val fastCodes = setOf(Pids.RPM.code, Pids.SPEED.code, Pids.THROTTLE.code, Pids.ENGINE_LOAD.code, Pids.MAF.code)
            var slowIndex = 0
            var timeouts = 0
            while (isActive) {
                val all = pollPids(_state.value)
                val fast = all.filter { it.code in fastCodes }
                val slow = all.filterNot { it.code in fastCodes }
                val round = fast + listOfNotNull(slow.getOrNull(slowIndex % maxOf(slow.size, 1)))
                slowIndex++
                for (pid in round) {
                    try {
                        val value = obd.read(pid)
                        timeouts = 0
                        if (value != null) {
                            _state.update {
                                val samples = (it.history[pid.code].orEmpty() + value.toFloat()).takeLast(HISTORY_SIZE)
                                it.copy(live = it.live + (pid.code to value), history = it.history + (pid.code to samples))
                            }
                        }
                    } catch (e: ElmTimeoutException) {
                        if (++timeouts >= 3) return@launch lostConnection(e)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@launch lostConnection(e)
                    }
                }
                val live = _state.value.live
                trip.update(live[Pids.SPEED.code], live[Pids.MAF.code], live[Pids.FUEL_RATE.code], System.currentTimeMillis())
                publishTrip()
                writeRecordingRow()
                if (round.size <= 1) delay(500)
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
                val freezeFrame = if (codes.isNotEmpty()) runCatching { obd.readFreezeFrame() }.getOrNull() else null
                _state.update { it.copy(codes = CodesState(loaded = true, monitor = monitor, codes = codes, freezeFrame = freezeFrame)) }
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
            val info = runCatching { obd.readVehicleInfo() }.getOrNull()
            val voltage = runCatching { obd.batteryVoltage() }.getOrNull()
            _state.update {
                it.copy(
                    vin = info?.vin ?: it.vin,
                    vehicleInfo = info,
                    vinLoading = false,
                    batteryVoltage = voltage ?: it.batteryVoltage,
                )
            }
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

    // --- Recording live data to CSV ---

    private var recordWriter: BufferedWriter? = null
    private var recordPids: List<Pid> = emptyList()
    private var recordStart = 0L

    /** Starts writing every polling round to a CSV file (metric units) in the app's cache. */
    fun startRecording() {
        if (recordWriter != null || client == null) return
        val pids = dashboardPids(_state.value).takeIf { it.isNotEmpty() } ?: return
        val dir = File(getApplication<Application>().cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "obd-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv")
        val writer = runCatching { file.bufferedWriter() }.getOrNull() ?: return
        writer.write((listOf("time_s") + pids.map { "${it.name} (${it.unit})" }).joinToString(",") { "\"$it\"" })
        writer.newLine()
        recordWriter = writer
        recordPids = pids
        recordStart = System.currentTimeMillis()
        _state.update { it.copy(recording = true, recordedRows = 0, lastRecording = file) }
    }

    fun stopRecording() {
        val writer = recordWriter ?: return
        recordWriter = null
        runCatching { writer.close() }
        _state.update { it.copy(recording = false) }
    }

    private suspend fun writeRecordingRow() {
        val writer = recordWriter ?: return
        val live = _state.value.live
        val elapsed = (System.currentTimeMillis() - recordStart) / 1000.0
        val row = (listOf(String.format(Locale.US, "%.2f", elapsed)) +
            recordPids.map { pid -> live[pid.code]?.let { String.format(Locale.US, "%.${pid.decimals + 1}f", it) } ?: "" })
            .joinToString(",")
        val ok = withContext(Dispatchers.IO) { runCatching { writer.write(row); writer.newLine() }.isSuccess }
        if (ok) _state.update { it.copy(recordedRows = it.recordedRows + 1) } else stopRecording()
    }

    override fun onCleared() {
        stopRecording()
        stopBleScan()
        closeChannel()
    }
}
