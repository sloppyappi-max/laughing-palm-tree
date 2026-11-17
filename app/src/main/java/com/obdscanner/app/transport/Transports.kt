package com.obdscanner.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.obdscanner.core.ElmChannel
import com.obdscanner.core.StreamElmChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Where to find the adapter. */
sealed interface AdapterTarget {
    val label: String

    data class Bluetooth(val device: BluetoothDevice, val name: String) : AdapterTarget {
        override val label get() = name
    }

    data class Ble(val device: BluetoothDevice, val name: String) : AdapterTarget {
        override val label get() = "$name (Bluetooth LE)"
    }

    data class Wifi(val host: String = "192.168.0.10", val port: Int = 35000) : AdapterTarget {
        override val label get() = "Wi-Fi $host:$port"
    }

    data object Demo : AdapterTarget {
        override val label get() = "Demo car"
    }
}

/** Serial Port Profile — what ELM327 Bluetooth adapters speak. */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

object Transports {

    /**
     * Opens an RFCOMM connection to a paired ELM327. Cheap clones often
     * reject the secure socket, so fall back to an insecure one and finally
     * to channel 1 directly.
     */
    @SuppressLint("MissingPermission") // checked by the UI before we get here
    suspend fun bluetooth(device: BluetoothDevice): ElmChannel =
        withContext(Dispatchers.IO) {
            val attempts: List<() -> BluetoothSocket> = listOf(
                { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                {
                    device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                },
            )
            var lastError: Exception? = null
            for (create in attempts) {
                val socket = try {
                    create()
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
                try {
                    socket.connect()
                    return@withContext StreamElmChannel(socket.inputStream, socket.outputStream) { socket.close() }
                } catch (e: IOException) {
                    lastError = e
                    runCatching { socket.close() }
                }
            }
            throw IOException("Could not connect to ${device.address}. Is the adapter plugged in and the ignition on?", lastError)
        }

    /** Wi-Fi ELM327s act as an access point and listen on a TCP port (usually 192.168.0.10:35000). */
    suspend fun wifi(target: AdapterTarget.Wifi): ElmChannel = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(target.host, target.port), 5_000)
            socket.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw IOException("Could not reach ${target.host}:${target.port}. Are you connected to the adapter's Wi-Fi network?", e)
        }
        StreamElmChannel(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
    }
}
