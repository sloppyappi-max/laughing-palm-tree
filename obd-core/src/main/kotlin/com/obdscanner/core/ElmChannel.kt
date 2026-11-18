package com.obdscanner.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A text channel to an ELM327-compatible adapter. One command in, the raw
 * text the adapter printed before its `>` prompt out.
 */
interface ElmChannel : Closeable {
    suspend fun exchange(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String

    /**
     * Sends raw bytes and collects raw bytes until [complete] says the answer is whole or
     * [timeoutMs] passes; returns whatever arrived (possibly nothing). For adapters with a
     * binary native protocol, such as Innova's VCI frames.
     */
    suspend fun exchangeBytes(data: ByteArray, timeoutMs: Long, complete: (ByteArray) -> Boolean): ByteArray =
        throw UnsupportedOperationException("This connection doesn't support raw byte exchange")

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}

class ElmTimeoutException(command: String) : IOException("Adapter did not answer '$command' in time")

/**
 * [ElmChannel] over a pair of byte streams, as given by a Bluetooth RFCOMM
 * socket or a TCP socket (Wi-Fi adapters).
 *
 * Reads poll [InputStream.available] so a timeout works even on streams whose
 * blocking `read` cannot be interrupted (Bluetooth sockets).
 */
class StreamElmChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {},
) : ElmChannel {

    private val lock = Mutex()

    override suspend fun exchange(command: String, timeoutMs: Long): String = lock.withLock {
        withContext(Dispatchers.IO) {
            // Drop anything left over from a previous, timed-out command.
            while (input.available() > 0) input.read()

            output.write("$command\r".toByteArray(Charsets.US_ASCII))
            output.flush()

            val response = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                if (input.available() > 0) {
                    val b = input.read()
                    if (b < 0) throw IOException("Connection closed by adapter")
                    val c = b.toChar()
                    if (c == '>') break
                    if (b != 0) response.append(c) // some clones emit NUL bytes
                } else {
                    if (System.currentTimeMillis() > deadline) throw ElmTimeoutException(command)
                    delay(5)
                }
            }
            response.toString()
        }
    }

    override suspend fun exchangeBytes(data: ByteArray, timeoutMs: Long, complete: (ByteArray) -> Boolean): ByteArray =
        lock.withLock {
            withContext(Dispatchers.IO) {
                while (input.available() > 0) input.read()
                output.write(data)
                output.flush()
                val buffer = java.io.ByteArrayOutputStream()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (input.available() > 0) {
                        val b = input.read()
                        if (b < 0) throw IOException("Connection closed by adapter")
                        buffer.write(b)
                        if (complete(buffer.toByteArray())) break
                    } else {
                        delay(5)
                    }
                }
                buffer.toByteArray()
            }
        }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { onClose() }
    }
}
