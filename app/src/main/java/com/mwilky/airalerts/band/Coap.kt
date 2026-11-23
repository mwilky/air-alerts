package com.mwilky.airalerts.band

/**
 * The little of CoAP (RFC 7252) the Fitbit Air needs: confirmable requests with a Uri-Path and a
 * body, and parsing the replies.
 */
internal object Coap {
    const val TYPE_CON = 0
    const val TYPE_NON = 1
    const val TYPE_ACK = 2
    const val TYPE_RST = 3

    const val POST = 2
    const val PUT = 3

    /** 4.04 Not Found, for requests the band sends us. */
    const val NOT_FOUND = (4 shl 5) or 4

    private const val OPTION_URI_PATH = 11

    data class Message(
        val type: Int,
        val code: Int,
        val messageId: Int,
        val token: ByteArray,
        val payload: ByteArray
    ) {
        val isRequest get() = code in 1..31
        val isSuccess get() = code shr 5 == 2
        val codeText get() = "${code shr 5}.%02d".format(code and 0x1F)
    }

    fun request(method: Int, path: String, messageId: Int, token: ByteArray, body: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out += ((1 shl 6) or (TYPE_CON shl 4) or token.size).toByte()
        out += method.toByte()
        out += (messageId shr 8).toByte()
        out += messageId.toByte()
        out += token.toList()
        var lastOption = 0
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { segment ->
            val value = segment.encodeToByteArray()
            out += optionHeader(OPTION_URI_PATH - lastOption, value.size)
            out += value.toList()
            lastOption = OPTION_URI_PATH
        }
        if (body.isNotEmpty()) {
            out += 0xFF.toByte()
            out += body.toList()
        }
        return out.toByteArray()
    }

    /** An empty-bodied reply to a confirmable request from the band. */
    fun ack(messageId: Int, token: ByteArray, code: Int): ByteArray =
        byteArrayOf(
            ((1 shl 6) or (TYPE_ACK shl 4) or token.size).toByte(),
            code.toByte(),
            (messageId shr 8).toByte(),
            messageId.toByte()
        ) + token

    fun parse(data: ByteArray): Message? {
        if (data.size < 4 || (data[0].toInt() shr 6) and 3 != 1) return null
        val type = (data[0].toInt() shr 4) and 3
        val tokenLength = data[0].toInt() and 0x0F
        if (tokenLength > 8 || data.size < 4 + tokenLength) return null
        val code = data[1].toInt() and 0xFF
        val messageId = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val token = data.copyOfRange(4, 4 + tokenLength)
        var i = 4 + tokenLength
        while (i < data.size) {
            val header = data[i].toInt() and 0xFF
            if (header == 0xFF) return Message(type, code, messageId, token, data.copyOfRange(i + 1, data.size))
            i++
            val (deltaExtra, deltaSize) = extended(data, i, header shr 4) ?: return null
            i += deltaSize
            val (length, lengthSize) = extended(data, i, header and 0x0F) ?: return null
            i += lengthSize + length
            if (deltaExtra < 0 || i > data.size) return null
        }
        return Message(type, code, messageId, token, ByteArray(0))
    }

    private fun optionHeader(delta: Int, length: Int): List<Byte> {
        fun nibble(v: Int) = when {
            v < 13 -> v
            v < 269 -> 13
            else -> 14
        }
        fun extra(v: Int) = when {
            v < 13 -> emptyList()
            v < 269 -> listOf((v - 13).toByte())
            else -> listOf(((v - 269) shr 8).toByte(), (v - 269).toByte())
        }
        return listOf(((nibble(delta) shl 4) or nibble(length)).toByte()) + extra(delta) + extra(length)
    }

    /** Reads an option delta or length nibble with its extended bytes: (value, extra bytes used). */
    private fun extended(data: ByteArray, at: Int, nibble: Int): Pair<Int, Int>? = when (nibble) {
        13 -> if (at < data.size) ((data[at].toInt() and 0xFF) + 13) to 1 else null
        14 -> if (at + 1 < data.size) ((((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)) + 269) to 2 else null
        15 -> null
        else -> nibble to 0
    }
}
