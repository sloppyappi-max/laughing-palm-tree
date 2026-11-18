package com.obdscanner.core

/**
 * Innova VCI ("passthru") native protocol, as used by the RepairSolutions2 SDK
 * (com.innova.sdk_core.protocol.passthru.*). Replaces the previous InnovaVci.
 *
 * Request frame (app -> dongle, written to the dongle's WRITE characteristic):
 *   AE  cks  lenLo lenHi  payload...
 *   cks = (lenLo + lenHi + sum(payload)) and 0xFF
 *   payload = 00 <commandId> <params...>
 *
 * Reply frame (dongle -> app, NOTIFY characteristic, may arrive in several notifications):
 *   EA  b1  lenLo lenHi  h0 h1 h2 h3  body...
 *   A frame is complete when buffered bytes >= 4 + len. RS2 never verifies b1 and never
 *   interprets h0..h3; it only uses `body`.
 *
 * Multi-byte integers inside payloads are little-endian, except CAN/bus addresses which
 * are big-endian 4-byte values.
 */
object InnovaVci {

    const val REQUEST_MARKER = 0xAE
    const val REPLY_MARKER = 0xEA

    // PassThruCommandEnum ids (com.innova.sdk_core.obd2.domain.command)
    const val CMD_AUTOLINK = 0x01
    const val CMD_WRITE = 0x02
    const val CMD_WRITE_PERIODIC = 0x03
    const val CMD_FILTER = 0x04
    const val CMD_SYSTEM = 0x05
    const val CMD_PROT_CONFIG = 0x06
    const val CMD_UPGRADE = 0x07
    const val CMD_PROT_CONFIG_FULL = 0x08
    const val CMD_OEM_SPECIFIC = 0x09
    const val CMD_ATE = 0x0A
    const val CMD_EVENT_REGISTER = 0x0B
    const val CMD_WRITE_DB = 0x0C
    const val CMD_WRITE_ID = 0x66

    // PASSTHRU_CMD_SYSTEM sub-commands (first SystemData byte) used by RS2's SystemProtocol
    const val SYS_STATUS = 0x01          // vbat() and dlcStatus() both send this
    const val SYS_BATTERY_CLEAR = 0x02   // batteryClearStatus()  -- clears state, never send
    const val SYS_BATTERY_STATUS = 0x03  // batteryGetStatus()
    const val SYS_GUID_PRODUCT_ID = 0x04 // getGuidProductId()
    const val SYS_FW_VERSION = 0x05      // getFwVersion()

    // PassThruDataTypeEnum
    const val TYPE_ADDRESS = 0x01
    const val TYPE_PROT_CONFIG = 0x02
    const val TYPE_OBD_DATA = 0x03

    /** Flag RS2 ORs into every OBD request it sends through PASSTHRU_CMD_WRITE. */
    const val FLAG_OBD_REQUEST = 0x20
    /** Set by RS2 when WriteData.header == true. */
    const val FLAG_HEADER = 0x200

    // Protocol ids returned by AUTOLINK (com.innova.sdk_core.enums.Protocol)
    const val PROTO_NONE = 0
    const val PROTO_VPW = 1
    const val PROTO_PWM = 2
    const val PROTO_ISO9141 = 3
    const val PROTO_KWP2000 = 4
    const val PROTO_CAN500 = 5
    const val PROTO_XCAN500 = 6
    const val PROTO_CAN250 = 7
    const val PROTO_XCAN250 = 8
    const val PROTO_CAN = 0x1D

    data class Frame(val payload: ByteArray, val checksumOk: Boolean, val marker: Int = REPLY_MARKER) {
        /** Reply body after the 4 header bytes RS2 skips (only meaningful for replies). */
        val body: ByteArray get() = if (payload.size > 4) payload.copyOfRange(4, payload.size) else ByteArray(0)
        override fun equals(other: Any?) =
            other is Frame && payload.contentEquals(other.payload) && checksumOk == other.checksumOk && marker == other.marker
        override fun hashCode() = (payload.contentHashCode() * 31 + checksumOk.hashCode()) * 31 + marker
    }

    /** One vehicle response message: source bus id (e.g. 0x7E8 or 0x18DAF110) + reassembled data. */
    data class Message(val id: Int, val data: ByteArray) {
        val is29Bit: Boolean get() = (id.toLong() and 0xFFFFFFFFL) > 0x7FF
        override fun equals(other: Any?) = other is Message && id == other.id && data.contentEquals(other.data)
        override fun hashCode() = id * 31 + data.contentHashCode()
    }

    // ---------------------------------------------------------------- framing

    fun checksum(payload: ByteArray): Int {
        val lo = payload.size and 0xFF
        val hi = (payload.size shr 8) and 0xFF
        var sum = lo + hi
        for (b in payload) sum += b.toInt() and 0xFF
        return sum and 0xFF
    }

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size <= 0xFFFF) { "Payload too long" }
        return byteArrayOf(
            REQUEST_MARKER.toByte(),
            checksum(payload).toByte(),
            (payload.size and 0xFF).toByte(),
            ((payload.size shr 8) and 0xFF).toByte(),
        ) + payload
    }

    fun command(cmd: Int, params: ByteArray = ByteArray(0)): ByteArray =
        frame(byteArrayOf(0, cmd.toByte()) + params)

    // ---------------------------------------------------------------- read-only requests

    /** AE 03 02 00 00 01 */
    val AUTOLINK: ByteArray get() = command(CMD_AUTOLINK)
    /** AE 0D 03 00 00 05 05 */
    val GET_FW_VERSION: ByteArray get() = command(CMD_SYSTEM, byteArrayOf(SYS_FW_VERSION.toByte()))
    /** AE 0C 03 00 00 05 04 */
    val GET_GUID_PRODUCT_ID: ByteArray get() = command(CMD_SYSTEM, byteArrayOf(SYS_GUID_PRODUCT_ID.toByte()))
    /** AE 09 03 00 00 05 01 */
    val GET_STATUS: ByteArray get() = command(CMD_SYSTEM, byteArrayOf(SYS_STATUS.toByte()))

    /**
     * PASSTHRU_CMD_WRITE: send one vehicle request (e.g. 09 02) on [protocol] to [address].
     *
     * payload = 00 02 | (n+1) LE16 | 03 | n LE16 | obd
     * obd     = protocol LE16 | flags LE32 | (data.size + 4) LE16 | address BE32 | data
     * where n = obd.size.
     */
    fun write(
        protocol: Int,
        address: Int,
        data: ByteArray,
        flags: Int = FLAG_OBD_REQUEST,
        header: Boolean = false,
    ): ByteArray {
        val f = if (header) flags or FLAG_HEADER else flags
        val obd = le16(protocol) + le32(f) + le16(data.size + 4) + be32(address) + data
        val n = obd.size
        val payload = byteArrayOf(0, CMD_WRITE.toByte()) +
            le16(n + 1) + byteArrayOf(TYPE_OBD_DATA.toByte()) + le16(n) + obd
        return frame(payload)
    }

    /** Functional (broadcast) request address RS2 uses per protocol (Obd2Spec.getObd2DefaultAddress). */
    fun defaultAddress(protocol: Int): Int = when (protocol) {
        PROTO_VPW -> 0x616AF1
        PROTO_CAN500, PROTO_CAN -> 0x7DF
        else -> 0
    }

    fun isCan(protocol: Int) = protocol in setOf(PROTO_CAN500, PROTO_XCAN500, PROTO_CAN250, PROTO_XCAN250, PROTO_CAN)

    // ---------------------------------------------------------------- safety guard

    /**
     * Commands that change, reboot, reflash or reconfigure the dongle (or are unimplemented in RS2).
     * Every frame leaving this app should pass [assertSafe].
     */
    val FORBIDDEN_COMMANDS = setOf(
        CMD_WRITE_PERIODIC, CMD_FILTER, CMD_PROT_CONFIG, CMD_UPGRADE, CMD_PROT_CONFIG_FULL,
        CMD_OEM_SPECIFIC, CMD_ATE, CMD_EVENT_REGISTER, CMD_WRITE_DB, CMD_WRITE_ID,
    )
    val ALLOWED_SYSTEM_SUBS = setOf(SYS_STATUS, SYS_BATTERY_STATUS, SYS_GUID_PRODUCT_ID, SYS_FW_VERSION)

    fun isSafe(frame: ByteArray): Boolean {
        if (frame.size < 6 || u(frame, 0) != REQUEST_MARKER) return true // not a VCI frame (e.g. ELM text)
        val cmd = u(frame, 5)
        if (cmd in FORBIDDEN_COMMANDS) return false
        if (cmd == CMD_SYSTEM) return frame.size >= 7 && u(frame, 6) in ALLOWED_SYSTEM_SUBS
        return cmd == CMD_AUTOLINK || cmd == CMD_WRITE
    }

    fun assertSafe(frame: ByteArray) {
        require(isSafe(frame)) { "Refusing to send a dongle write/reboot/update command: ${frame.hex()}" }
    }

    // ---------------------------------------------------------------- reply decoding

    /**
     * Splits [bytes] into frames. Accepts dongle replies (0xEA) and, for logging of echoes,
     * request-style frames (0xAE). Returns the frames and the index of the first unconsumed byte.
     */
    fun decode(bytes: ByteArray): Pair<List<Frame>, Int> {
        val frames = mutableListOf<Frame>()
        var i = 0
        var consumed = 0
        while (i < bytes.size) {
            val m = u(bytes, i)
            if (m != REPLY_MARKER && m != REQUEST_MARKER) { i++; consumed = i; continue }
            if (i + 4 > bytes.size) break
            val len = u(bytes, i + 2) or (u(bytes, i + 3) shl 8)
            if (i + 4 + len > bytes.size) break
            val payload = bytes.copyOfRange(i + 4, i + 4 + len)
            frames += Frame(payload, checksum(payload) == u(bytes, i + 1), m)
            i += 4 + len
            consumed = i
        }
        return frames to consumed
    }

    fun hasCompleteFrame(bytes: ByteArray): Boolean = decode(bytes).first.isNotEmpty()

    /** True once [bytes] holds at least one complete 0xEA reply frame. Use as exchangeBytes' completion test. */
    fun hasCompleteReply(bytes: ByteArray): Boolean = decode(bytes).first.any { it.marker == REPLY_MARKER }

    fun replies(bytes: ByteArray): List<Frame> = decode(bytes).first.filter { it.marker == REPLY_MARKER }

    /** AUTOLINK reply -> protocol id (0 = no vehicle link). body[2] must be TYPE_PROT_CONFIG. */
    fun parseAutoLink(body: ByteArray): Int =
        if (body.size >= 6 && u(body, 2) == TYPE_PROT_CONFIG) u(body, 4) or (u(body, 5) shl 8) else PROTO_NONE

    /**
     * PASSTHRU_CMD_WRITE reply -> vehicle messages.
     * body = total LE16 | records...; record = 03 | ? LE16 | protocol LE16 | flags LE32 | n LE16 | msg[n]
     * msg  = source address BE32 | response bytes (ISO-TP already reassembled by the dongle).
     * Like RS2, the first ECM-looking response (0x7E8, 0x18DAF110, ...) is moved to the front.
     */
    fun parseWrite(body: ByteArray): List<Message> {
        if (body.size < 2) return emptyList()
        val total = u(body, 0) or (u(body, 1) shl 8)
        var rest = body.copyOfRange(2, body.size)
        var used = 0
        val raw = mutableListOf<ByteArray>()
        while (used < total && rest.size >= 11 && u(rest, 0) == TYPE_OBD_DATA) {
            val n = u(rest, 9) or (u(rest, 10) shl 8)
            val end = minOf(11 + n, rest.size)
            val msg = rest.copyOfRange(11, end)
            if (msg.isNotEmpty()) raw += msg
            used += 11 + n
            rest = if (end < rest.size) rest.copyOfRange(end, rest.size) else ByteArray(0)
        }
        if (raw.size > 1) {
            val ecm = raw.indexOfFirst { it.size >= 4 && isEcmAddress(be32At(it, 0)) }
            if (ecm > 0) { val t = raw[0]; raw[0] = raw[ecm]; raw[ecm] = t }
        }
        return raw.filter { it.size >= 4 }.map { Message(be32At(it, 0), it.copyOfRange(4, it.size)) }
    }

    fun isEcmAddress(id: Int): Boolean =
        id == 0x7E8 || id == 0x18DAF110 || id == 0x18DAF10E || id == 0x18DAF111 || id == 0x18DAF117 ||
            (id and 0xFF) in setOf(0x10, 0x11, 0x12)

    // ---------------------------------------------------------------- helpers

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )
    private fun be32(v: Int) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )
    private fun be32At(b: ByteArray, i: Int) =
        (u(b, i) shl 24) or (u(b, i + 1) shl 16) or (u(b, i + 2) shl 8) or u(b, i + 3)

    fun ByteArray.hex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
