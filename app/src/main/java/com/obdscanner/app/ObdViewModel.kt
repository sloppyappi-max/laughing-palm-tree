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
import com.obdscanner.core.CustomSensor
import com.obdscanner.core.FreezeFrame
import com.obdscanner.core.ManufacturerPids
import com.obdscanner.core.SystemScan
import com.obdscanner.core.Units
import com.obdscanner.core.VehicleIdentity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
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

data class BleDevice(val name: String, val address: String, val rssi: Int, val named: Boolean = true) {
    /** Names OBD adapters typically advertise (ELM327 clones, Innova, Vgate, OBDLink, …). */
    val looksLikeAdapter: Boolean
        get() = named && Regex("OBD|ELM|INNOVA|VLINK|V-LINK|VGATE|ICAR|KONNWEI|VEEPEAK|CARISTA|BLCKTEC|\\bRS\\d", RegexOption.IGNORE_CASE)
            .containsMatchIn(name)
}

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
    val settings: AppSettings = AppSettings(units = UnitSystem.METRIC),
    /** Recent samples per PID (metric), oldest first, for the dashboard graphs. */
    val history: Map<Int, List<Float>> = emptyMap(),
    val recording: Boolean = false,
    val recordedRows: Int = 0,
    val lastRecording: File? = null,
    val savedAdapter: SavedAdapter? = null,
    val showAllSensors: Boolean = false,
    val trip: TripState = TripState(),
    val monitorTests: TestsState = TestsState(),
    val vehicleInfo: VehicleInfo? = null,
    /** True on 11-bit CAN cars, where module-addressed (manufacturer / custom) requests work. */
    val moduleAccess: Boolean = false,
    /** Custom sensors plus built-in manufacturer sensors for this car, as pollable PIDs. */
    val customPids: List<Pid> = emptyList(),
    val scan: ScanState = ScanState(),
    val explorer: ExplorerState = ExplorerState(),
    val vehicleRetrying: Boolean = false,
    val adapterTestRunning: Boolean = false,
    /** Raw output of the adapter test, for troubleshooting. */
    val adapterReport: String? = null,
) {
    val units: UnitSystem get() = settings.units
    val favorites: List<Int> get() = settings.favorites

    /** Any sensor by code: standard Mode 01 or custom / manufacturer. */
    fun pid(code: Int): Pid? = Pids.byCode(code) ?: customPids.firstOrNull { it.code == code }

    /** Whether this car can report [pid]. */
    fun available(pid: Pid): Boolean = if (pid.isCustom) moduleAccess else pid.code in supportedPids

    /** Sensors currently outside the user's alert limits. */
    val triggeredAlerts: List<Pair<Pid, Double>>
        get() = settings.alerts.mapNotNull { (code, alert) ->
            val value = live[code] ?: return@mapNotNull null
            val pid = pid(code) ?: return@mapNotNull null
            if (alert.isTriggered(value)) pid to value else null
        }
}

data class ScanState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val result: SystemScan? = null,
    val full: Boolean = false,
)

data class ExplorerState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val results: Map<Int, ByteArray>? = null,
    val header: String = "7E0",
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
    private val settingsStore = SettingsStore(prefs)

    private val _state = MutableStateFlow(
        UiState(settings = settingsStore.load(), savedAdapter = loadSavedAdapter()).let { it.copy(customPids = buildCustomPids(it)) },
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val bluetooth: BluetoothAdapter? =
        app.getSystemService(BluetoothManager::class.java)?.adapter

    private var channel: ElmChannel? = null
    private var client: ObdClient? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var liveActive = false

    /**
     * Parent of all work for the current connection (connecting, polling, reading codes, scans…).
     * Cancelled on disconnect so a request still in flight can't update the screen afterwards.
     */
    private var connectionJob = SupervisorJob(viewModelScope.coroutineContext[Job])

    private fun launchForConnection(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(connectionJob, block = block)

    private fun resetConnectionJobs() {
        val old = connectionJob
        connectionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        old.cancel()
    }

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

    fun setUnits(units: UnitSystem) = updateSettings { it.copy(units = units) }

    /** Applies and persists a settings change. */
    fun updateSettings(change: (AppSettings) -> AppSettings) {
        _state.update { s ->
            val next = s.copy(settings = change(s.settings).also(settingsStore::save))
            next.copy(customPids = buildCustomPids(next))
        }
    }

    /** User sensors, plus GM sensors when the VIN says GM (or in demo mode). Bad formulas are skipped. */
    private fun buildCustomPids(state: UiState): List<Pid> {
        val make = VehicleIdentity.fromVin(state.vin).make
        val demo = (state.connection as? ConnectionState.Connected)?.target == AdapterTarget.Demo
        val gm = make in setOf("Chevrolet", "GMC", "Cadillac", "Buick") || demo
        val sensors = state.settings.customSensors +
            (if (state.settings.manufacturerExtras && gm) ManufacturerPids.GM else emptyList())
        return sensors.mapNotNull { runCatching { it.toPid() }.getOrNull() }
    }

    fun saveCustomSensor(sensor: CustomSensor) = updateSettings { s ->
        s.copy(customSensors = s.customSensors.filterNot { it.id == sensor.id } + sensor)
    }

    fun deleteCustomSensor(id: Int) = updateSettings { s ->
        val code = CustomSensor.CUSTOM_BASE + id
        s.copy(
            customSensors = s.customSensors.filterNot { it.id == id },
            favorites = s.favorites - code,
            gauges = s.gauges - code,
            alerts = s.alerts - code,
        )
    }

    /** Reads a sensor once so the editor can show what it returns. */
    fun testCustomSensor(sensor: CustomSensor, onResult: (String) -> Unit) {
        val obd = client ?: return onResult("Connect to the car first.")
        if (!obd.supportsModuleAccess) return onResult("This car's protocol doesn't support module requests.")
        val pid = runCatching { sensor.toPid() }.getOrElse { return onResult(it.message ?: "Invalid sensor") }
        launchForConnection {
            val value = runCatching { obd.read(pid) }.getOrNull()
            ensureActive()
            onResult(
                if (value == null) "No answer. Check the module address and command."
                else "Reads ${Units.display(pid, value, _state.value.units).let { "${it.text} ${it.unit}" }}",
            )
        }
    }

    // --- All-system scan and ID explorer ---

    fun scanModules(full: Boolean) {
        val obd = client ?: return
        if (_state.value.scan.running) return
        launchForConnection {
            _state.update { it.copy(scan = ScanState(running = true, full = full)) }
            val result = runCatching {
                obd.scanModules(full) { p -> _state.update { it.copy(scan = it.scan.copy(progress = p)) } }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                if (failIfLinkLost(e)) return@launchForConnection
                SystemScan(emptyList(), "Scan failed: ${e.message}")
            }
            ensureActive()
            _state.update { it.copy(scan = ScanState(result = result, full = full)) }
        }
    }

    fun exploreDids(header: String, from: Int, to: Int) {
        val obd = client ?: return
        val id = header.toIntOrNull(16) ?: return
        if (_state.value.explorer.running) return
        launchForConnection {
            _state.update { it.copy(explorer = ExplorerState(running = true, header = header)) }
            val found = runCatching {
                obd.exploreDids(id, from, to) { p -> _state.update { it.copy(explorer = it.explorer.copy(progress = p)) } }
            }.getOrElse { e -> if (e is CancellationException) throw e; emptyMap() }
            ensureActive()
            _state.update { it.copy(explorer = ExplorerState(results = found, header = header)) }
        }
    }

    /** Moves a favorite one place earlier (-1) or later (+1) on the dashboard. */
    fun moveFavorite(code: Int, direction: Int) = updateSettings { s ->
        val list = s.favorites.toMutableList()
        val from = list.indexOf(code)
        val to = from + direction
        if (from < 0 || to !in list.indices) return@updateSettings s
        list.add(to, list.removeAt(from))
        s.copy(favorites = list)
    }

    fun toggleGauge(code: Int) = updateSettings { s ->
        s.copy(gauges = if (code in s.gauges) s.gauges - code else s.gauges + code)
    }

    /** Sets or clears (both null) the alert limits for a sensor, in metric units. */
    fun setAlert(code: Int, low: Double?, high: Double?) = updateSettings { s ->
        s.copy(alerts = if (low == null && high == null) s.alerts - code else s.alerts + (code to SensorAlert(low, high)))
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
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                val device = BleDevice(name ?: "Unnamed device", result.device.address, result.rssi, named = name != null)
                _state.update { s ->
                    val list = s.bleDevices.filterNot { it.address == device.address } + device
                    // Likely adapters first, then named devices, each by signal strength.
                    s.copy(bleDevices = list.sortedWith(compareByDescending<BleDevice> { it.looksLikeAdapter }.thenByDescending { it.named }.thenByDescending { it.rssi }))
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
        connectJob = launchForConnection {
            try {
                _state.update { it.copy(connection = ConnectionState.Connecting(target, "Opening connection…")) }
                // Socket connects can't be interrupted; let it finish, then close it if the user cancelled
                // meanwhile, so the adapter isn't left busy.
                val ch = withContext(NonCancellable) { open() }
                if (!isActive) {
                    runCatching { ch.close() }
                    return@launchForConnection
                }
                channel = ch
                val obd = ObdClient(ch)
                _state.update { it.copy(connection = ConnectionState.Connecting(target, "Initializing adapter…")) }
                val info = obd.initialize()
                client = obd
                showVehicle(obd, target, info)
                saved?.let(::saveAdapter)
                if (liveActive) startPolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launchForConnection // cancelled: the user already moved on
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

    /** After connecting (or retrying): reads what the car supports and shows it. */
    private suspend fun CoroutineScope.showVehicle(obd: ObdClient, target: AdapterTarget, info: AdapterInfo) {
        val supported = if (info.vehicleResponding) obd.supportedPids() else emptySet()
        // The VIN tells us the year and make, used to search trouble codes online.
        val vin = if (info.vehicleResponding) runCatching { obd.readVin() }.getOrNull() else null
        // Check-engine light status for the status line under every screen title.
        val monitor = if (info.vehicleResponding) runCatching { obd.monitorStatus() }.getOrNull() else null
        ensureActive()
        _state.update {
            it.copy(
                connection = ConnectionState.Connected(target, info),
                supportedPids = supported,
                codes = CodesState(monitor = monitor),
                vin = vin,
                batteryVoltage = info.batteryVoltage,
                moduleAccess = obd.supportsModuleAccess,
            ).let { s -> s.copy(customPids = buildCustomPids(s)) }
        }
    }

    /**
     * Runs car detection again without reconnecting to the adapter — for when the car didn't answer
     * at first (ignition was off, or the adapter's automatic protocol search failed).
     */
    fun retryVehicle() {
        val obd = client ?: return
        val connected = _state.value.connection as? ConnectionState.Connected ?: return
        if (_state.value.vehicleRetrying) return
        launchForConnection {
            _state.update { it.copy(vehicleRetrying = true) }
            try {
                val info = obd.detectVehicle(connected.info.elmVersion)
                ensureActive()
                showVehicle(obd, connected.target, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive) return@launchForConnection
                lostConnection(e)
            } finally {
                _state.update { it.copy(vehicleRetrying = false) }
            }
        }
    }

    /** Runs the read-only adapter test sequence and keeps its raw output for sharing. */
    fun runAdapterTest() {
        val obd = client ?: return
        if (_state.value.adapterTestRunning) return
        launchForConnection {
            _state.update { it.copy(adapterTestRunning = true) }
            val report = runCatching { obd.adapterReport() }.getOrElse { e ->
                if (e is CancellationException) throw e
                if (failIfLinkLost(e)) return@launchForConnection
                "Adapter test failed: ${e.message}"
            }
            ensureActive()
            val info = (_state.value.connection as? ConnectionState.Connected)?.info
            val header = listOfNotNull(
                "OBD Scanner adapter test",
                info?.probeResponse?.let { "Detection:\n$it" },
            ).joinToString("\n\n")
            _state.update { it.copy(adapterTestRunning = false, adapterReport = "$header\n\n$report") }
        }
    }

    fun disconnect() {
        resetConnectionJobs()
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
                moduleAccess = false,
                scan = ScanState(),
                explorer = ExplorerState(),
                trip = TripState(),
                vin = null,
                vinLoading = false,
                batteryVoltage = null,
                terminal = emptyList(),
                diagnostics = null,
                adapterReport = null,
                adapterTestRunning = false,
                vehicleRetrying = false,
            )
        }
    }

    private fun closeChannel() {
        runCatching { channel?.close() }
        channel = null
        client = null
    }

    private fun lostConnection(e: Exception) {
        stopRecording()
        closeChannel()
        trip.reset()
        _state.update {
            it.copy(
                connection = ConnectionState.Failed(
                    "Lost the connection to the adapter (${e.message ?: e.javaClass.simpleName}). " +
                        "Check it's still plugged in and close any other OBD app, then reconnect.",
                ),
            )
        }
        resetConnectionJobs() // also ends the polling loop that called this
    }

    /**
     * True when [e] means the link to the adapter itself is gone (socket closed, "Broken pipe",
     * Bluetooth LE disconnect) rather than the car not answering in time.
     */
    private fun isLinkFailure(e: Throwable): Boolean =
        (e is java.io.IOException && e !is ElmTimeoutException) ||
            e is kotlinx.coroutines.channels.ClosedReceiveChannelException

    /** For one-off requests: a dead link ends the connection instead of showing an error forever. */
    private fun CoroutineScope.failIfLinkLost(e: Throwable): Boolean {
        if (!isActive || !isLinkFailure(e)) return false
        lostConnection(e as? Exception ?: java.io.IOException(e))
        return true
    }

    /** The dashboard calls this when it becomes visible / hidden, so we only poll while someone is looking. */
    fun setLiveDataActive(active: Boolean) {
        liveActive = active
        if (active) startPolling() else pollJob?.cancel()
    }

    /** PIDs shown on the dashboard: favorites, or every supported sensor in "All sensors" mode. */
    fun dashboardPids(state: UiState): List<Pid> =
        if (state.showAllSensors) {
            Pids.ALL.filter { it.code in state.supportedPids } + state.customPids.filter(state::available)
        } else {
            state.favorites.mapNotNull(state::pid).filter(state::available)
        }

    /** Big round gauges at the top of the dashboard, in the user's order. */
    fun gaugePids(state: UiState): List<Pid> =
        state.settings.gauges.mapNotNull(state::pid).filter { state.available(it) && it.label == null }

    /** What to poll: gauges, visible sensors, sensors with alerts, and what the trip computer needs. */
    private fun pollPids(state: UiState): List<Pid> {
        val extra = (listOf(Pids.SPEED, Pids.MAF, Pids.FUEL_RATE) + state.settings.alerts.keys.mapNotNull(state::pid))
            .filter(state::available)
        return (gaugePids(state) + dashboardPids(state) + extra).distinct()
    }

    fun toggleFavorite(code: Int) = updateSettings { s ->
        s.copy(favorites = if (code in s.favorites) s.favorites - code else s.favorites + code)
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
        launchForConnection {
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
                if (failIfLinkLost(e)) return@launchForConnection
                _state.update { it.copy(monitorTests = it.monitorTests.copy(loading = false, message = "Could not read test results: ${e.message}")) }
            }
        }
    }

    private fun startPolling() {
        val obd = client ?: return
        if (pollJob?.isActive == true) return
        pollJob = launchForConnection {
            // Refresh fast-changing values every round, and one slower value per round.
            // The list is re-read every round so favorites / "All sensors" apply immediately.
            // Fuel rate is "fast" too: the trip computer integrates it every round.
            val fastCodes = setOf(
                Pids.RPM.code, Pids.SPEED.code, Pids.THROTTLE.code, Pids.ENGINE_LOAD.code, Pids.MAF.code, Pids.FUEL_RATE.code,
            )
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
                        if (!isActive) return@launchForConnection
                        if (++timeouts >= 3) return@launchForConnection lostConnection(e)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Closing the channel on disconnect makes a pending read fail; that isn't a lost connection.
                        if (!isActive) return@launchForConnection
                        return@launchForConnection lostConnection(e)
                    }
                }
                val live = _state.value.live
                trip.update(live[Pids.SPEED.code], live[Pids.MAF.code], live[Pids.FUEL_RATE.code], System.currentTimeMillis())
                publishTrip()
                writeRecordingRow()
                if (round.isEmpty()) {
                    // Nothing to read (e.g. the car isn't answering): check the adapter every few seconds
                    // so a dropped link is noticed, and keep the voltage current.
                    delay(4_000)
                    try {
                        obd.batteryVoltage()?.let { v -> _state.update { it.copy(batteryVoltage = v) } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (failIfLinkLost(e)) return@launchForConnection
                    }
                } else if (round.size <= 1) {
                    delay(500)
                }
            }
        }
    }

    fun readCodes() {
        val obd = client ?: return
        launchForConnection {
            _state.update { it.copy(codes = it.codes.copy(loading = true, message = null)) }
            try {
                val monitor = obd.monitorStatus()
                val codes = DtcType.entries.flatMap { obd.readDtcs(it) }
                val freezeFrame = if (codes.isNotEmpty()) runCatching { obd.readFreezeFrame() }.getOrNull() else null
                _state.update { it.copy(codes = CodesState(loaded = true, monitor = monitor, codes = codes, freezeFrame = freezeFrame)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failIfLinkLost(e)) return@launchForConnection
                _state.update { it.copy(codes = it.codes.copy(loading = false, message = "Could not read codes: ${e.message}")) }
            }
        }
    }

    fun clearCodes() {
        val obd = client ?: return
        launchForConnection {
            _state.update { it.copy(codes = it.codes.copy(loading = true, message = null)) }
            val ok = runCatching { obd.clearDtcs() }.getOrDefault(false)
            ensureActive()
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
        launchForConnection {
            _state.update { it.copy(vinLoading = true) }
            val infoResult = runCatching { obd.readVehicleInfo() }
            infoResult.exceptionOrNull()?.let { if (failIfLinkLost(it)) return@launchForConnection }
            val info = infoResult.getOrNull()
            val voltage = runCatching { obd.batteryVoltage() }.getOrNull()
            ensureActive()
            _state.update {
                it.copy(
                    vin = info?.vin ?: it.vin,
                    vehicleInfo = info,
                    vinLoading = false,
                    batteryVoltage = voltage ?: it.batteryVoltage,
                ).let { s -> s.copy(customPids = buildCustomPids(s)) } // GM sensors depend on the VIN
            }
        }
    }

    /** Raw command console for power users. */
    fun sendRaw(command: String) {
        val obd = client ?: return
        val cmd = command.trim().uppercase()
        if (cmd.isEmpty()) return
        launchForConnection {
            val reply = runCatching { obd.raw(cmd).joinToString("\n").ifEmpty { "(empty)" } }
                .getOrElse { e ->
                    if (failIfLinkLost(e)) return@launchForConnection
                    "Error: ${e.message}"
                }
            ensureActive()
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
