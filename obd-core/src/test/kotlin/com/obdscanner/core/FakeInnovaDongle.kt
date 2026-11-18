package com.obdscanner.core

/**
 * A simulated Innova dongle that speaks only the native protocol, in the reply format documented
 * from RepairSolutions2. Vehicle answers come from [SimulatedElmChannel], so every OBD service
 * the app uses behaves realistically. Records every frame it receives.
 */
class FakeInnovaDongle(
    private val car: SimulatedElmChannel = SimulatedElmChannel(latencyMs = 0),
    private val vehicleConnected: Boolean = true,
) : ElmChannel {

    val received = mutableListOf<ByteArray>()

    override suspend fun exchange(command: String, timeoutMs: Long): String =
        error("A native Innova dongle doesn't take text commands (got '$command')")

    override suspend fun exchangeBytes(data: ByteArray, timeoutMs: Long, complete: (ByteArray) -> Boolean): ByteArray {
        received += data
        val request = InnovaVci.decode(data).first.singleOrNull() ?: return ByteArray(0)
        val p = request.payload
        return when (p.getOrNull(1)?.toInt()) {
            InnovaVci.CMD_AUTOLINK -> reply(byteArrayOf(0, 0, 2, 0) + le16(if (vehicleConnected) InnovaVci.PROTO_CAN else 0))
            InnovaVci.CMD_WRITE -> writeReply(p)
            InnovaVci.CMD_SYSTEM -> reply("FAKE 1.0".toByteArray())
            else -> ByteArray(0)
        }
    }

    /** payload = 00 02 | (n+1) LE16 | 03 | n LE16 | protocol LE16 | flags LE32 | len LE16 | address BE32 | data */
    private suspend fun writeReply(p: ByteArray): ByteArray {
        val address = ((p[15].toInt() and 0xFF) shl 24) or ((p[16].toInt() and 0xFF) shl 16) or
            ((p[17].toInt() and 0xFF) shl 8) or (p[18].toInt() and 0xFF)
        val data = p.copyOfRange(19, p.size)
        // Ask the simulated car, addressed like the request.
        car.exchange("ATSH%03X".format(address))
        val command = data.joinToString("") { "%02X".format(it) }
        val raw = car.exchange(command)
        val source = if (address == 0x7DF) 0x7E8 else if (address < 0x300) address + 0x400 else address + 8
        // A real VCI relays whatever the module sends, including negative responses (7F …), which
        // ObdResponse.parse drops — so recover those from the raw text when there's no positive data.
        val messages = (ElmResponseParser.parse(raw, command) as? ObdResponse.Data)?.messages?.takeIf { it.isNotEmpty() }
            ?: ElmResponseParser.lines(raw, command).mapNotNull { line ->
                val h = line.replace(" ", "").uppercase()
                if (h.length >= 2 && h.length % 2 == 0 && h.all { it in "0123456789ABCDEF" }) {
                    ElmResponseParser.stripCanHeader(h) ?: ElmResponseParser.hexToBytes(h)
                } else {
                    null
                }
            }
        val records = messages.fold(ByteArray(0)) { acc, m ->
            val msg = be32(source) + m
            acc + byteArrayOf(InnovaVci.TYPE_OBD_DATA.toByte(), 0, 0) + le16(InnovaVci.PROTO_CAN) +
                byteArrayOf(0, 0, 0, 0) + le16(msg.size) + msg
        }
        return reply(le16(records.size) + records)
    }

    private fun reply(body: ByteArray): ByteArray {
        val payload = byteArrayOf(0x00, 0x02, 0x00, 0x00) + body
        return byteArrayOf(0xEA.toByte(), 0, (payload.size and 0xFF).toByte(), (payload.size shr 8).toByte()) + payload
    }

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    override fun close() = Unit
}
