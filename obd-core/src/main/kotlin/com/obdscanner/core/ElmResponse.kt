package com.obdscanner.core

/** Result of parsing what an ELM327 printed for one OBD request. */
sealed interface ObdResponse {
    /** One byte array per ECU message (multi-frame CAN messages already joined). */
    data class Data(val messages: List<ByteArray>) : ObdResponse {
        /** Messages answering [mode]/[pid] (positive response = mode + 0x40). */
        fun matching(mode: Int, pid: Int? = null): List<ByteArray> = messages.filter { m ->
            m.isNotEmpty() && m.u(0) == mode + 0x40 && (pid == null || (m.size > 1 && m.u(1) == pid))
        }
    }

    /** The car did not answer (e.g. unsupported PID, ignition off). */
    data object NoData : ObdResponse

    /** The adapter reported an error. */
    data class Error(val message: String) : ObdResponse
}

internal fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

object ElmResponseParser {

    private val errorMarkers = listOf(
        "UNABLE TO CONNECT", "BUS INIT", "BUS BUSY", "BUS ERROR", "CAN ERROR", "DATA ERROR",
        "FB ERROR", "LV RESET", "STOPPED", "BUFFER FULL", "ERR", "?",
    )

    private val hexLine = Regex("^[0-9A-F]+$")
    private val canLengthLine = Regex("^[0-9A-F]{3}$")
    private val canFrameLine = Regex("^([0-9A-F]):([0-9A-F]+)$")

    /** Splits raw adapter output into trimmed, non-empty lines, dropping the echoed [command]. */
    fun lines(raw: String, command: String? = null): List<String> =
        raw.replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { command != null && it.replace(" ", "").equals(command.replace(" ", ""), ignoreCase = true) }

    fun parse(raw: String, command: String? = null): ObdResponse {
        val lines = lines(raw, command)
            .map { it.uppercase() }
            .filterNot { it.startsWith("SEARCHING") }
            // "BUS INIT: ...OK" is a progress line; "BUS INIT: ...ERROR" is a failure.
            .filterNot { it.startsWith("BUS INIT") && it.endsWith("OK") }

        if (lines.isEmpty() || lines.any { it.replace(" ", "") == "NODATA" }) return ObdResponse.NoData
        lines.firstOrNull { line -> errorMarkers.any { line.startsWith(it) } }?.let { return ObdResponse.Error(it) }

        val messages = mutableListOf<ByteArray>()
        var multiFrame: StringBuilder? = null
        var multiFrameLength = 0

        fun finishMultiFrame() {
            val frame = multiFrame ?: return
            val bytes = hexToBytes(frame.toString())
            messages += bytes.copyOf(minOf(multiFrameLength, bytes.size))
            multiFrame = null
        }

        // Raw ISO-TP frames: headers on ("7E8 10 14 49 02 …" then "7E8 21 …"), keyed by sender.
        val isoTp = LinkedHashMap<String, Pair<Int, java.io.ByteArrayOutputStream>>() // header -> (length, data)
        // Innova-style headers-off output: the first frame without its PCI bytes, then each
        // consecutive frame with its "2N" sequence byte still in front ("490201…", "21…", "22…").
        var openMessage: java.io.ByteArrayOutputStream? = null
        var nextSequence = 1

        fun finishOpenMessage() {
            openMessage?.let { messages += it.toByteArray() }
            openMessage = null
        }

        for (rawLine in lines) {
            val line = rawLine.replace(" ", "")
            val frameMatch = canFrameLine.matchEntire(line)
            val headerFrame = if (hexLine.matches(line)) isoTpFrame(line) else null
            if (headerFrame != null) {
                finishMultiFrame(); finishOpenMessage()
                val (header, bytes) = headerFrame
                val pci = bytes.u(0)
                when (pci shr 4) {
                    1 -> if (bytes.size >= 2) {
                        val buffer = java.io.ByteArrayOutputStream().apply { write(bytes, 2, bytes.size - 2) }
                        isoTp[header] = (((pci and 0x0F) shl 8) or bytes.u(1)) to buffer
                    }
                    2 -> isoTp[header]?.second?.write(bytes, 1, bytes.size - 1)
                }
                continue
            }
            val continuation = openMessage != null && hexLine.matches(line) && line.length % 2 == 0 &&
                line.length in 4..16 && line[0] == '2' && line[1].digitToInt(16) == nextSequence % 16
            if (continuation) {
                openMessage!!.write(hexToBytes(line.substring(2)))
                nextSequence++
                continue
            }
            finishOpenMessage()
            when {
                // ISO 15765 multi-frame, headers off: "014" then "0:...", "1:..."
                canLengthLine.matches(line) -> {
                    finishMultiFrame()
                    multiFrame = StringBuilder()
                    multiFrameLength = line.toInt(16)
                }
                frameMatch != null && multiFrame != null -> multiFrame!!.append(frameMatch.groupValues[2])
                hexLine.matches(line) && stripCanHeader(line) != null -> {
                    // Adapters that ignore ATH0 (some brand dongles) still put the sender's ID in front.
                    finishMultiFrame()
                    messages += stripCanHeader(line)!!
                }
                hexLine.matches(line) && line.length % 2 == 0 -> {
                    finishMultiFrame()
                    val bytes = hexToBytes(line)
                    // A full first-frame payload (6 bytes) may be followed by "21…" continuation lines.
                    if (bytes.size == 6) {
                        openMessage = java.io.ByteArrayOutputStream().apply { write(bytes) }
                        nextSequence = 1
                    } else {
                        messages += bytes
                    }
                }
                else -> Unit // ignore unrecognised noise
            }
        }
        finishMultiFrame()
        finishOpenMessage()
        for ((length, data) in isoTp.values) {
            val bytes = data.toByteArray()
            messages += bytes.copyOf(minOf(length, bytes.size))
        }

        // Negative responses (7F <mode> <code>) mean the ECU rejected the request.
        val positive = messages.filterNot { it.isNotEmpty() && it.u(0) == 0x7F }
        return if (positive.isEmpty()) ObdResponse.NoData else ObdResponse.Data(positive)
    }

    /**
     * Recognises a single-frame CAN answer printed with headers on — 11-bit `7E8 06 41 00 …` or
     * 29-bit `18DAF110 06 41 00 …` — and returns just the data bytes. The length byte must match
     * the rest of the line, so ordinary header-less data is never mistaken for it.
     */
    fun stripCanHeader(line: String): ByteArray? {
        val headerLength = when {
            line.length % 2 == 1 && line.length >= 7 && line[0] == '7' -> 3 // 11-bit diagnostic IDs 7xx
            line.length >= 12 && line.startsWith("18DA") -> 8 // 29-bit; no positive answer starts with 0x18
            else -> return null
        }
        val rest = hexToBytes(line.substring(headerLength))
        if (rest.isEmpty()) return null
        val length = rest.u(0)
        if (length !in 1..7 || length > rest.size - 1) return null
        return rest.copyOfRange(1, 1 + length)
    }

    /**
     * A first or consecutive ISO-TP frame printed with its 11-bit header (`7E8 10 14 …`, `7E8 21 …`):
     * returns (header, frame bytes starting at the PCI byte). Single frames are left to [stripCanHeader].
     */
    private fun isoTpFrame(line: String): Pair<String, ByteArray>? {
        val headerLength = when {
            line.length % 2 == 1 && line.length >= 7 && line[0] == '7' -> 3
            line.length >= 14 && line.startsWith("18DA") -> 8
            else -> return null
        }
        val bytes = hexToBytes(line.substring(headerLength))
        if (bytes.size < 2 || bytes.size > 8) return null
        return when (bytes.u(0) shr 4) {
            1 -> if (bytes.size == 8) line.substring(0, headerLength) to bytes else null
            2 -> line.substring(0, headerLength) to bytes
            else -> null
        }
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else hex.dropLast(1)
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
