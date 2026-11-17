package com.obdscanner.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.obdscanner.core.ElmChannel
import com.obdscanner.core.ElmTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/** Records what happened on a Bluetooth LE link so an unknown adapter can be investigated. */
class BleLog {
    private val lines = mutableListOf<String>()
    private var trafficLines = 0

    @Synchronized
    fun add(line: String) {
        lines += line
    }

    @Synchronized
    fun traffic(direction: String, bytes: ByteArray) {
        if (trafficLines++ >= 200) return
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        val ascii = bytes.map { b -> (b.toInt() and 0xFF).let { if (it in 0x20..0x7E) it.toChar() else '.' } }.joinToString("")
        lines += "$direction $hex  |$ascii|"
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}

private fun uuid16(short: Int): UUID = UUID.fromString("0000%04X-0000-1000-8000-00805F9B34FB".format(short))

private val CCCD: UUID = uuid16(0x2902)

/** Service / notify characteristic / write characteristic used by known BLE ELM327 adapters. */
private val KNOWN_LAYOUTS = listOf(
    Triple(uuid16(0xFFF0), uuid16(0xFFF1), uuid16(0xFFF2)), // most cheap BLE clones
    Triple(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE1)), // HM-10 style modules
    Triple(uuid16(0x18F0), uuid16(0x2AF0), uuid16(0x2AF1)), // Vgate iCar Pro BLE
    Triple( // OBDLink CX / MX+ (BLE)
        UUID.fromString("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"),
        UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
        UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F"),
    ),
)

/** Standard services that never carry adapter data. */
private val IGNORED_SERVICES = setOf(
    uuid16(0x1800), uuid16(0x1801), uuid16(0x180A), uuid16(0x180F),
    // Firmware-update services: never write to these.
    uuid16(0xFE59), // Nordic secure DFU
    UUID.fromString("00001530-1212-EFDE-1523-785FEABCD123"), // Nordic legacy DFU
    UUID.fromString("F000FFC0-0451-4000-B000-000000000000"), // TI OAD
    UUID.fromString("1D14D6EE-FD63-4FA1-BFA4-8F47B42119F0"), // Silicon Labs OTA
    UUID.fromString("00010203-0405-0607-0809-0A0B0C0D1912"), // Telink OTA
)

/** Like `withTimeout { await() }`, but a timeout is an ordinary connection error, not a cancellation. */
private suspend fun <T : Any> CompletableDeferred<T>.awaitWithin(ms: Long, what: String): T =
    withTimeoutOrNull(ms) { await() } ?: throw IOException("Timed out waiting for $what")

private fun BluetoothGattCharacteristic.can(flag: Int) = properties and flag != 0

private fun describeProperties(c: BluetoothGattCharacteristic): String = buildList {
    if (c.can(BluetoothGattCharacteristic.PROPERTY_READ)) add("read")
    if (c.can(BluetoothGattCharacteristic.PROPERTY_WRITE)) add("write")
    if (c.can(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) add("writeNoResp")
    if (c.can(BluetoothGattCharacteristic.PROPERTY_NOTIFY)) add("notify")
    if (c.can(BluetoothGattCharacteristic.PROPERTY_INDICATE)) add("indicate")
}.joinToString(",")

/**
 * [ElmChannel] over Bluetooth LE: commands are written to one characteristic
 * and the adapter's text arrives as notifications on another.
 */
@SuppressLint("MissingPermission") // the UI requests BLUETOOTH_CONNECT before connecting
class BleElmChannel private constructor(private val log: BleLog) : ElmChannel, BluetoothGattCallback() {

    private lateinit var gatt: BluetoothGatt
    private lateinit var writeChar: BluetoothGattCharacteristic

    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Unit>()
    @Volatile private var pendingDescriptor: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var failure: IOException? = null
    @Volatile private var pendingMtu: CompletableDeferred<Int>? = null

    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val lock = Mutex()

    // --- GATT callbacks (binder threads) ---

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            log.add("connected")
            connected.complete(Unit)
        } else {
            val e = IOException(
                "Bluetooth LE link closed (status $status). If this is a brand dongle (e.g. Innova): close its own app " +
                    "first, and if you paired it in Android's Bluetooth settings, remove it there.",
            )
            log.add("disconnected: status=$status state=$newState")
            failure = e
            connected.completeExceptionally(e)
            discovered.completeExceptionally(e)
            pendingWrite?.completeExceptionally(e)
            pendingDescriptor?.completeExceptionally(e)
            incoming.close(e)
        }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
        else discovered.completeExceptionally(IOException("Service discovery failed (status $status)"))
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
        pendingMtu?.complete(mtu)
    }

    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
        pendingDescriptor?.complete(status == BluetoothGatt.GATT_SUCCESS)
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) log.add("write to ${c.uuid} failed: status $status")
        pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
    }

    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        received(value)
    }

    @Deprecated("Used on Android 12 and lower")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) received(c.value ?: return)
    }

    private fun received(value: ByteArray) {
        log.traffic("RX", value)
        incoming.trySend(value.copyOf())
    }

    // --- ElmChannel ---

    override suspend fun exchange(command: String, timeoutMs: Long): String = lock.withLock {
        failure?.let { throw it }
        while (incoming.tryReceive().isSuccess) Unit // drop stale data
        val bytes = "$command\r".toByteArray(Charsets.US_ASCII)
        log.traffic("TX", bytes)
        write(bytes)

        val response = StringBuilder()
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val chunk = incoming.receive()
                val text = String(chunk, Charsets.ISO_8859_1)
                val prompt = text.indexOf('>')
                if (prompt >= 0) {
                    response.append(text, 0, prompt)
                    break
                }
                response.append(text)
            }
        } ?: throw ElmTimeoutException(command)
        response.toString().replace("\u0000", "")
    }

    private suspend fun write(data: ByteArray) {
        val type = if (writeChar.can(BluetoothGattCharacteristic.PROPERTY_WRITE)) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        for (start in data.indices step 20) {
            val chunk = data.copyOfRange(start, minOf(start + 20, data.size))
            val done = CompletableDeferred<Boolean>()
            pendingWrite = done
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(writeChar, chunk, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    writeChar.writeType = type
                    writeChar.value = chunk
                    gatt.writeCharacteristic(writeChar)
                }
            }
            if (!started) throw IOException("Could not write to the adapter")
            if (!done.awaitWithin(3_000, "a write to finish")) throw IOException("Adapter rejected a write")
        }
    }

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        incoming.close()
    }

    // --- setup ---

    private suspend fun open(context: Context, device: BluetoothDevice) {
        log.add("device: ${runCatching { device.name }.getOrNull()} ${device.address}")
        gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        connected.awaitWithin(15_000, "the Bluetooth LE connection")
        if (!gatt.discoverServices()) throw IOException("Could not start service discovery")
        discovered.awaitWithin(15_000, "service discovery")

        for (service in gatt.services) {
            log.add("service ${service.uuid}")
            for (c in service.characteristics) log.add("  char ${c.uuid} [${describeProperties(c)}]")
        }

        // Bigger packets help adapters that send whole answers at once; ignore if refused.
        val mtu = CompletableDeferred<Int>()
        pendingMtu = mtu
        if (gatt.requestMtu(247)) withTimeoutOrNull(2_000) { mtu.await() }?.let { log.add("mtu $it") }

        val candidates = candidateLinks()
        if (candidates.isEmpty()) throw IOException("This device has no characteristics that look like a serial data link.")

        // Brand dongles (Innova, BlueDriver-style…) can expose several data services. Try each and keep
        // the one that answers a harmless "identify" command (ATI) like an ELM327 does.
        for ((notify, write) in candidates) {
            log.add("trying notify=${notify.uuid} write=${write.uuid}")
            try {
                enableNotifications(notify)
                writeChar = write
                val answer = exchange("ATI", 2_500)
                log.add("ATI answered: ${answer.trim().replace("\r", " | ")}")
                log.add("using notify=${notify.uuid} write=${write.uuid}")
                return
            } catch (e: IOException) {
                log.add("no ELM327 answer: ${e.message}")
                runCatching { gatt.setCharacteristicNotification(notify, false) }
                failure?.let { throw it } // link dropped: stop trying
            }
        }
        throw IOException(
            "Connected, but the adapter didn't answer like an ELM327 on any channel. " +
                "Copy the diagnostics report below and share it so support for it can be added.",
        )
    }

    /** Notify/write pairs worth trying: known adapter layouts first, then every other plausible pair. */
    private fun candidateLinks(): List<Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>> {
        val out = mutableListOf<Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>>()
        for ((serviceId, notifyId, writeId) in KNOWN_LAYOUTS) {
            val service = gatt.getService(serviceId) ?: continue
            val notify = service.getCharacteristic(notifyId) ?: continue
            val write = service.getCharacteristic(writeId) ?: continue
            out += notify to write
        }
        val notifyFlags = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        val writeFlags = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        for (service in gatt.services) {
            if (service.uuid in IGNORED_SERVICES) continue
            val notifies = service.characteristics.filter { it.can(notifyFlags) }
            val writes = service.characteristics.filter { it.can(writeFlags) }
            for (n in notifies) for (w in writes) {
                if (out.none { it.first.uuid == n.uuid && it.second.uuid == w.uuid }) out += n to w
            }
        }
        return out.take(8) // enough for any real adapter, and keeps a broken device from taking forever
    }

    private suspend fun enableNotifications(c: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(c, true)) throw IOException("Could not subscribe to adapter data")
        val descriptor = c.getDescriptor(CCCD) ?: return // some modules notify without a CCCD
        val value = if (c.can(BluetoothGattCharacteristic.PROPERTY_NOTIFY)) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val done = CompletableDeferred<Boolean>()
        pendingDescriptor = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
        if (!started || !done.awaitWithin(5_000, "notifications to be enabled")) throw IOException("Could not enable adapter notifications")
    }

    companion object {
        suspend fun connect(context: Context, device: BluetoothDevice, log: BleLog): ElmChannel {
            val channel = BleElmChannel(log)
            try {
                channel.open(context.applicationContext, device)
            } catch (e: Exception) {
                log.add("error: ${e.message}")
                runCatching { channel.close() }
                throw e
            }
            return channel
        }
    }
}
