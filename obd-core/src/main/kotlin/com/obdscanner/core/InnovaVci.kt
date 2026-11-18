package com.obdscanner.core

/**
 * Innova "VCI" dongle protocol (Innova 3210RS/3215RS and similar), as used by RepairSolutions2.
 *
 * The dongle's ELM327 emulation answers AT commands but refuses vehicle requests (7F 01 11).
 * Its native protocol is a framed binary "passthru" protocol on the same Bluetooth channel
 * (BLE service FFF0: write FFF1, notify FFF2):
 *
 * ```
 * AE  checksum  lenLo  lenHi  payload...
 * checksum = (lenLo + lenHi + Σ payload) & 0xFF
 * payload  = 00  commandId  params...
 * ```
 *
 * Only read-only commands are exposed here. Commands that change the dongle (UPGRADE, WRITE_ID,
 * WRITE_DB, SYSTEM reboot / bootloader) are deliberately absent.
 */
object InnovaVci {
    const val START_OF_FRAME = 0xAE

    /** Passthru command IDs (RepairSolutions2 `PassThruCommandEnum`); only the safe ones are named. */
    const val CMD_AUTOLINK = 1
    const val CMD_WRITE = 2
    const val CMD_FILTER = 4
    const val CMD_SYSTEM = 5

    /** SYSTEM sub-command: read firmware version (read-only). */
    const val SYSTEM_GET_FW_VERSION = 5

    /** Wraps a payload in a frame. */
    fun frame(payload: ByteArray): ByteArray {
        require(payload.size <= 0xFFFF) { "Payload too long" }
        val lenLo = payload.size and 0xFF
        val lenHi = (payload.size shr 8) and 0xFF
        val checksum = (lenLo + lenHi + payload.sumOf { it.toInt() and 0xFF }) and 0xFF
        return byteArrayOf(START_OF_FRAME.toByte(), checksum.toByte(), lenLo.toByte(), lenHi.toByte()) + payload
    }

    /** A passthru command: payload `00 <commandId> <params>`. */
    fun command(commandId: Int, params: ByteArray = ByteArray(0)): ByteArray =
        frame(byteArrayOf(0x00, commandId.toByte()) + params)

    /** `AE 0D 03 00 00 05 05` — read the dongle's firmware version. */
    val GET_FW_VERSION: ByteArray get() = command(CMD_SYSTEM, byteArrayOf(SYSTEM_GET_FW_VERSION.toByte()))

    /** One frame decoded from the dongle. */
    data class Frame(val payload: ByteArray, val checksumOk: Boolean) {
        override fun equals(other: Any?) = other is Frame && payload.contentEquals(other.payload) && checksumOk == other.checksumOk
        override fun hashCode() = payload.contentHashCode() * 31 + checksumOk.hashCode()
    }

    /**
     * Finds complete frames in [bytes] (bytes before a start marker are skipped).
     * Returns the frames and how many bytes were consumed, so a caller can keep the rest
     * (a partial frame) for when more data arrives.
     */
    fun decode(bytes: ByteArray): Pair<List<Frame>, Int> {
        val frames = mutableListOf<Frame>()
        var i = 0
        var consumed = 0
        while (i < bytes.size) {
            if (bytes.u(i) != START_OF_FRAME) {
                i++
                consumed = i
                continue
            }
            if (i + 4 > bytes.size) break // header not complete yet
            val length = bytes.u(i + 2) or (bytes.u(i + 3) shl 8)
            if (i + 4 + length > bytes.size) break // payload not complete yet
            val payload = bytes.copyOfRange(i + 4, i + 4 + length)
            val expected = (bytes.u(i + 2) + bytes.u(i + 3) + payload.sumOf { it.toInt() and 0xFF }) and 0xFF
            frames += Frame(payload, expected == bytes.u(i + 1))
            i += 4 + length
            consumed = i
        }
        return frames to consumed
    }

    /** True once [bytes] holds at least one complete frame. */
    fun hasCompleteFrame(bytes: ByteArray): Boolean = decode(bytes).first.isNotEmpty()
}
