package com.obdscanner.core

import com.obdscanner.core.InnovaVci.Message
import com.obdscanner.core.InnovaVci.hex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lets the existing ELM327-based [ObdClient] drive an Innova VCI that speaks the native
 * RepairSolutions2 protocol. Upward it answers like an ELM327; downward it sends
 * InnovaVci frames through [link] (the BLE channel's raw byte exchange).
 *
 * Session, mirroring RS2: no wake-up/AT handshake. After notifications are on, the first
 * frame is AUTOLINK (AE 03 02 00 00 01); every OBD request is then a PASSTHRU_CMD_WRITE.
 *
 * Only AUTOLINK, WRITE and read-only SYSTEM queries are ever sent (see InnovaVci.assertSafe).
 */
class InnovaNativeChannel(
    private val link: ElmChannel,
    private val log: (String) -> Unit = {},
) : ElmChannel {

    private val mutex = Mutex()

    // vehicle link (from AUTOLINK)
    private var protocol: Int = InnovaVci.PROTO_NONE
    private var last29Bit = false

    /** When the last AUTOLINK found no vehicle; avoids repeating 20 s attempts back to back. */
    private var linkFailedAt = 0L

    // emulated ELM state
    private var headers = false
    private var spaces = true
    private var autoFormat = true
    private var txHeader: Int? = null
    private var canPriority = 0x18
    private var rxFilter: Int? = null
    private var rxMaskFilter: Pair<Int, Int>? = null // (filter, mask)

    companion object {
        const val ELM_ID = "ELM327 v1.5"
        const val AUTOLINK_TIMEOUT_MS = 20_000L   // RS2 TimeoutConfig.MEDIUM
        const val MIN_REQUEST_TIMEOUT_MS = 3_000L
        const val RELINK_PAUSE_MS = 30_000L
        private const val CR = "\r"

        /** Sends the read-only firmware query; true if the dongle answers with a native 0xEA frame. */
        suspend fun probe(link: ElmChannel, timeoutMs: Long = 2_000L): Boolean = try {
            val rx = link.exchangeBytes(InnovaVci.GET_FW_VERSION, timeoutMs) { InnovaVci.hasCompleteReply(it) }
            InnovaVci.replies(rx).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /** Closing this layer closes the Bluetooth link underneath (disconnect). */
    override fun close() = link.close()

    override suspend fun exchangeBytes(bytes: ByteArray, timeoutMs: Long, complete: (ByteArray) -> Boolean): ByteArray {
        InnovaVci.assertSafe(bytes)
        return mutex.withLock { link.exchangeBytes(bytes, timeoutMs, complete) }
    }

    override suspend fun exchange(command: String, timeoutMs: Long): String = mutex.withLock {
        val cmd = command.trim().uppercase().replace(" ", "")
        if (cmd.isEmpty()) return@withLock ""
        if (cmd.startsWith("AT")) return@withLock at(cmd.substring(2))
        if (cmd == "STI" || cmd == "VTI") return@withLock "?"
        val bytes = parseHex(cmd) ?: return@withLock "?"
        obdRequest(bytes, timeoutMs)
    }

    // ---------------------------------------------------------------- AT emulation

    private suspend fun at(a: String): String = when {
        a == "Z" || a == "WS" || a == "D" -> { resetState(); linkFailedAt = 0L; if (a == "D") "OK" else ELM_ID }
        a == "I" -> ELM_ID
        a == "@1" -> "Innova VCI (native)"
        a == "@2" -> "?"
        a == "H0" -> { headers = false; "OK" }
        a == "H1" -> { headers = true; "OK" }
        a == "S0" -> { spaces = false; "OK" }
        a == "S1" -> { spaces = true; "OK" }
        a == "CAF0" -> { autoFormat = false; "OK" }
        a == "CAF1" -> { autoFormat = true; "OK" }
        a.startsWith("SH") -> setHeader(a.substring(2))
        a.startsWith("CP") -> { a.substring(2).toIntOrNull(16)?.let { canPriority = it and 0x1F }; "OK" }
        a == "CRA" -> { rxFilter = null; "OK" }
        a.startsWith("CRA") -> { rxFilter = a.substring(3).toIntOrNull(16); "OK" }
        a.startsWith("CF") -> { a.substring(2).toIntOrNull(16)?.let { rxMaskFilter = it to (rxMaskFilter?.second ?: -1) }; "OK" }
        a.startsWith("CM") -> { a.substring(2).toIntOrNull(16)?.let { rxMaskFilter = (rxMaskFilter?.first ?: 0) to it }; "OK" }
        a == "AR" -> { rxFilter = null; rxMaskFilter = null; "OK" }
        a == "DPN" -> dpn()
        a == "DP" -> dp()
        a == "RV" -> "?"   // RS2 reads voltage via SYSTEM 01, but its reply layout is not decoded here
        a.startsWith("SP") || a.startsWith("TP") -> { protocol = InnovaVci.PROTO_NONE; "OK" } // relink on next request
        else -> "OK"       // E0, L0, ST, AT1, FCSH/FCSD/FCSM, PPS, CS, RD ... (dongle does ISO-TP itself)
    }

    private fun resetState() {
        headers = false; spaces = true; autoFormat = true
        txHeader = null; canPriority = 0x18; rxFilter = null; rxMaskFilter = null
        protocol = InnovaVci.PROTO_NONE
    }

    private fun setHeader(h: String): String {
        val v = h.toIntOrNull(16) ?: return "?"
        txHeader = when (h.length) {
            3 -> v and 0x7FF
            6 -> (canPriority shl 24) or v
            8 -> v
            else -> return "?"
        }
        return "OK"
    }

    private fun dpn(): String = when (protocol) {
        InnovaVci.PROTO_PWM -> "A1"
        InnovaVci.PROTO_VPW -> "A2"
        InnovaVci.PROTO_ISO9141 -> "A3"
        InnovaVci.PROTO_KWP2000 -> "A5"
        InnovaVci.PROTO_CAN250, InnovaVci.PROTO_XCAN250 -> if (last29Bit) "A9" else "A8"
        InnovaVci.PROTO_CAN500, InnovaVci.PROTO_XCAN500, InnovaVci.PROTO_CAN -> if (last29Bit) "A7" else "A6"
        else -> "A0"
    }

    private fun dp(): String = when (dpn()) {
        "A1" -> "AUTO, SAE J1850 PWM"
        "A2" -> "AUTO, SAE J1850 VPW"
        "A3" -> "AUTO, ISO 9141-2"
        "A5" -> "AUTO, ISO 14230-4 (KWP FAST)"
        "A6" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        "A7" -> "AUTO, ISO 15765-4 (CAN 29/500)"
        "A8" -> "AUTO, ISO 15765-4 (CAN 11/250)"
        "A9" -> "AUTO, ISO 15765-4 (CAN 29/250)"
        else -> "AUTO"
    }

    // ---------------------------------------------------------------- native session

    private suspend fun ensureLinked(): Boolean {
        if (protocol != InnovaVci.PROTO_NONE) return true
        // The app's protocol search asks several times in a row; after a failed link, answer
        // "no vehicle" at once for a while instead of waiting another 20 s each time.
        if (System.currentTimeMillis() - linkFailedAt < RELINK_PAUSE_MS) return false
        repeat(3) { attempt ->
            val rx = link.exchangeBytes(InnovaVci.AUTOLINK, AUTOLINK_TIMEOUT_MS) { InnovaVci.hasCompleteReply(it) }
            val reply = InnovaVci.replies(rx).firstOrNull()
            log("AUTOLINK #${attempt + 1} RX ${rx.hex().ifEmpty { "(nothing)" }}")
            if (reply == null) { // the dongle didn't answer at all: retrying won't help
                linkFailedAt = System.currentTimeMillis()
                return false
            }
            val p = InnovaVci.parseAutoLink(reply.body)
            if (p != InnovaVci.PROTO_NONE) { protocol = p; linkFailedAt = 0L; log("linked, protocol id $p"); return true }
            // Answered "no vehicle found": RS2 retries up to 3 times in that case.
        }
        linkFailedAt = System.currentTimeMillis()
        return false
    }

    private suspend fun obdRequest(requestIn: ByteArray, timeoutMs: Long): String {
        var request = requestIn
        // With CAF0 the ELM client sends its own ISO-TP PCI byte; the dongle wants bare data.
        if (!autoFormat && request.size >= 2 && (request[0].toInt() and 0xF0) == 0 &&
            (request[0].toInt() and 0x0F) == request.size - 1
        ) request = request.copyOfRange(1, request.size)

        if (!ensureLinked()) return "UNABLE TO CONNECT"

        val address = txHeader ?: InnovaVci.defaultAddress(protocol)
        val frame = InnovaVci.write(protocol, address, request)
        InnovaVci.assertSafe(frame)
        val rx = link.exchangeBytes(frame, maxOf(timeoutMs, MIN_REQUEST_TIMEOUT_MS)) { InnovaVci.hasCompleteReply(it) }
        log("TX ${frame.hex()} RX ${rx.hex()}")

        val messages = InnovaVci.replies(rx).flatMap { InnovaVci.parseWrite(it.body) }.filter(::passesFilter)
        if (messages.isEmpty()) return "NO DATA"
        last29Bit = messages.any { it.is29Bit }
        return messages.flatMap(::formatElm).joinToString(CR)
    }

    private fun passesFilter(m: Message): Boolean {
        rxFilter?.let { if (m.id != it) return false }
        rxMaskFilter?.let { (f, mask) -> if ((m.id and mask) != (f and mask)) return false }
        return true
    }

    // ---------------------------------------------------------------- ELM-style output

    private fun idText(id: Int): String =
        if ((id.toLong() and 0xFFFFFFFFL) <= 0x7FF) "%03X".format(id)
        else listOf(24, 16, 8, 0).joinToString(" ") { "%02X".format((id shr it) and 0xFF) }

    private fun bytesText(b: List<Int>) = b.joinToString(" ") { "%02X".format(it) }

    private fun line(text: String) = if (spaces) text else text.replace(" ", "")

    /** Re-segments a reassembled message into ELM327 CAN output (single frame or FF/CF lines). */
    private fun formatElm(m: Message): List<String> {
        val d = m.data.map { it.toInt() and 0xFF }
        val head = if (headers) idText(m.id) + " " else ""
        if (d.size <= 7) {
            val body = if (headers || !autoFormat) listOf(d.size) + d else d
            return listOf(line(head + bytesText(body)))
        }
        if (headers || !autoFormat) {
            val out = mutableListOf<String>()
            out += line(head + bytesText(listOf(0x10 or ((d.size shr 8) and 0x0F), d.size and 0xFF) + d.take(6)))
            var seq = 1
            var i = 6
            while (i < d.size) {
                out += line(head + bytesText(listOf(0x20 or (seq and 0x0F)) + d.subList(i, minOf(i + 7, d.size))))
                seq++; i += 7
            }
            return out
        }
        // headers off, CAF1: ELM's numbered multi-line format
        val out = mutableListOf("%03X".format(d.size))
        out += "0:" + (if (spaces) " " else "") + line(bytesText(d.take(6)))
        var seq = 1
        var i = 6
        while (i < d.size) {
            out += "${seq % 16}:" + (if (spaces) " " else "") + line(bytesText(d.subList(i, minOf(i + 7, d.size))))
            seq++; i += 7
        }
        return out
    }

    private fun parseHex(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.isEmpty() || s.any { it !in "0123456789ABCDEF" }) return null
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
