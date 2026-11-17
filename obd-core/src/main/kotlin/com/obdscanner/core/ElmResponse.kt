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

        for (rawLine in lines) {
            val line = rawLine.replace(" ", "")
            val frameMatch = canFrameLine.matchEntire(line)
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
                    messages += hexToBytes(line)
                }
                else -> Unit // ignore unrecognised noise
            }
        }
        finishMultiFrame()

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

    fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else hex.dropLast(1)
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
