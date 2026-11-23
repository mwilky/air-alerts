package com.mwilky.airalerts.band

import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The Fitbit Air opens its secure channel as a DTLS 1.2 client with a pre-shared key; the phone is
 * the server. Google Health holds the band's paired key, which we can't read, so the band is first
 * refused; it then starts over with Golden Gate's public bootstrap key, which we accept.
 */
internal object BandDtls {
    /** Golden Gate's bootstrap PSK (`BootstrapTlsKeyResolver.kt`, Apache-2.0). */
    private val BOOTSTRAP_IDENTITY = "BOOTSTRAP".encodeToByteArray()
    private val BOOTSTRAP_KEY = byteArrayOf(
        0x81.toByte(), 0x06, 0x54, 0xE3.toByte(), 0x36, 0xAD.toByte(), 0xCA.toByte(), 0xB0.toByte(),
        0xA0.toByte(), 0x3C, 0x60, 0xF7.toByte(), 0x4A, 0xA0.toByte(), 0xB6.toByte(), 0xFB.toByte()
    )

    // A handshake with the band takes about a second; longer means Google Health got in the way.
    private const val HANDSHAKE_TIMEOUT_MS = 4_000

    sealed interface Outcome {
        class Session(val transport: DTLSTransport) : Outcome

        /** The band offered a key we don't hold, normally its Google Health key. */
        data class WrongKey(val identity: String) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /**
     * Runs one handshake on [transport]. Blocks until it finishes, fails, or the transport closes.
     * With [refuseAll] every key is refused, which makes the band reset its link: that is how the
     * channel is handed back to Google Health.
     */
    fun accept(transport: DatagramTransport, refuseAll: Boolean = false): Outcome {
        var offered: String? = null
        val keys = object : TlsPSKIdentityManager {
            override fun getHint(): ByteArray? = null

            override fun getPSK(identity: ByteArray): ByteArray? {
                offered = identity.toPrintable()
                return if (!refuseAll && identity.contentEquals(BOOTSTRAP_IDENTITY)) BOOTSTRAP_KEY.copyOf() else null
            }
        }
        val server = object : PSKTlsServer(BcTlsCrypto(SecureRandom()), keys) {
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()

            // The band offers PSK_AES_128_CCM, PSK_AES_128_GCM_SHA256 and ECDHE_PSK_AES_128_CBC_SHA256;
            // Golden Gate's hub picks CCM.
            override fun getSupportedCipherSuites(): IntArray = intArrayOf(
                CipherSuite.TLS_PSK_WITH_AES_128_CCM,
                CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256
            )

            override fun getHandshakeTimeoutMillis(): Int = HANDSHAKE_TIMEOUT_MS
        }
        val protocol = DTLSServerProtocol().apply { verifyRequests = false }
        return try {
            Outcome.Session(protocol.accept(server, transport))
        } catch (e: TlsFatalAlert) {
            val identity = offered
            if (e.alertDescription == AlertDescription.unknown_psk_identity && identity != null) {
                Outcome.WrongKey(identity)
            } else {
                Outcome.Failed("${AlertDescription.getText(e.alertDescription)}${e.message?.let { ": $it" } ?: ""}")
            }
        } catch (e: IOException) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun ByteArray.toPrintable(): String =
        if (all { it in 0x20..0x7E }) decodeToString() else joinToString("") { "%02x".format(it) }
}

/**
 * UDP payloads in and out of the band, as BouncyCastle's DTLS sees them. BouncyCastle closes its
 * transport when a handshake fails, so each attempt gets its own, all reading one shared [inbox]:
 * the band's next ClientHello can then arrive before the next attempt starts without being lost.
 */
internal class BandDatagrams(
    private val inbox: LinkedBlockingQueue<ByteArray>,
    private val sendDatagram: (ByteArray) -> Unit
) : DatagramTransport {
    @Volatile
    private var closed = false

    override fun getReceiveLimit() = 1_500

    // 1280-byte IP MTU less the IPv4 and UDP headers.
    override fun getSendLimit() = 1_252

    override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
        if (closed) throw IOException("closed")
        val datagram = inbox.poll(waitMillis.toLong(), TimeUnit.MILLISECONDS)
            ?: if (closed) throw IOException("closed") else return -1
        val size = minOf(len, datagram.size)
        datagram.copyInto(buf, off, 0, size)
        return size
    }

    override fun send(buf: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("closed")
        sendDatagram(buf.copyOfRange(off, off + len))
    }

    override fun close() {
        closed = true
    }
}
