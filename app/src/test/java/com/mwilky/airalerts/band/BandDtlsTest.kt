package com.mwilky.airalerts.band

import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Our server against a BouncyCastle client offering what the Fitbit Air offers. */
class BandDtlsTest {
    private val bootstrapKey = byteArrayOf(
        0x81.toByte(), 0x06, 0x54, 0xE3.toByte(), 0x36, 0xAD.toByte(), 0xCA.toByte(), 0xB0.toByte(),
        0xA0.toByte(), 0x3C, 0x60, 0xF7.toByte(), 0x4A, 0xA0.toByte(), 0xB6.toByte(), 0xFB.toByte()
    )

    private class ClientSide(private val inbox: LinkedBlockingQueue<ByteArray>, private val out: LinkedBlockingQueue<ByteArray>) :
        DatagramTransport {
        override fun getReceiveLimit() = 1_500
        override fun getSendLimit() = 1_252
        override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
            val d = inbox.poll(waitMillis.toLong(), TimeUnit.MILLISECONDS) ?: return -1
            d.copyInto(buf, off)
            return d.size
        }
        override fun send(buf: ByteArray, off: Int, len: Int) {
            out.offer(buf.copyOfRange(off, off + len))
        }
        override fun close() = Unit
    }

    private fun bandClient(identity: String, key: ByteArray) =
        object : PSKTlsClient(BcTlsCrypto(SecureRandom()), BasicTlsPSKIdentity(identity, key)) {
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()
            override fun getSupportedCipherSuites() = intArrayOf(
                CipherSuite.TLS_PSK_WITH_AES_128_CCM,
                CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256
            )
        }

    private fun handshake(identity: String, key: ByteArray): BandDtls.Outcome {
        val toServer = LinkedBlockingQueue<ByteArray>()
        val toClient = LinkedBlockingQueue<ByteArray>()
        var clientMessage: String? = null
        val client = thread {
            try {
                val dtls = DTLSClientProtocol().connect(bandClient(identity, key), ClientSide(toClient, toServer))
                val ping = "ping".encodeToByteArray()
                dtls.send(ping, 0, ping.size)
            } catch (e: Exception) {
                clientMessage = e.toString()
            }
        }
        val outcome = BandDtls.accept(BandDatagrams(toServer) { toClient.offer(it) })
        if (outcome is BandDtls.Outcome.Session) {
            val buf = ByteArray(100)
            val n = outcome.transport.receive(buf, 0, buf.size, 2_000)
            assertEquals("ping", buf.decodeToString(0, n))
        }
        client.join(10_000)
        println("client: ${clientMessage ?: "ok"}")
        return outcome
    }

    @Test
    fun acceptsTheBootstrapKey() {
        val outcome = handshake("BOOTSTRAP", bootstrapKey)
        assertTrue("$outcome", outcome is BandDtls.Outcome.Session)
    }

    @Test
    fun reportsAnUnknownKey() {
        val outcome = handshake("google-health-key", ByteArray(16) { 7 })
        assertEquals(BandDtls.Outcome.WrongKey("google-health-key"), outcome)
    }
}
