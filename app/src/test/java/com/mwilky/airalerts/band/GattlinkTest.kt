package com.mwilky.airalerts.band

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GattlinkTest {
    /** A manual clock: timers run only when [advance] passes them. */
    private class FakeScheduler : Gattlink.Scheduler {
        var now = 0L
        private val timers = mutableListOf<Triple<Long, Int, () -> Unit>>()
        private var nextId = 0

        override fun schedule(delayMs: Long, action: () -> Unit): Gattlink.Cancellable {
            val id = nextId++
            timers += Triple(now + delayMs, id, action)
            return Gattlink.Cancellable { timers.removeAll { it.second == id } }
        }

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val due = timers.filter { it.first <= end }.minByOrNull { it.first } ?: break
                timers.remove(due)
                now = due.first
                due.third()
            }
            now = end
        }
    }

    /** Two ends wired together; packets are delivered when [pump] runs. */
    private class Pair(mtu: Int = 20, val drop: (from: String, index: Int, packet: ByteArray) -> Boolean = { _, _, _ -> false }) {
        val scheduler = FakeScheduler()
        val toB = ArrayDeque<ByteArray>()
        val toA = ArrayDeque<ByteArray>()
        val sentByA = mutableListOf<ByteArray>()
        val receivedByA = mutableListOf<Byte>()
        val receivedByB = mutableListOf<Byte>()
        var readyA = 0
        var readyB = 0

        val a = Gattlink(transport(mtu, "a", toB, sentByA), scheduler, listener({ readyA++ }, receivedByA))
        val b = Gattlink(transport(mtu, "b", toA, mutableListOf()), scheduler, listener({ readyB++ }, receivedByB))

        private fun transport(size: Int, name: String, queue: ArrayDeque<ByteArray>, log: MutableList<ByteArray>) =
            object : Gattlink.Transport {
                var count = 0
                override val maxPacketSize = size
                override fun send(packet: ByteArray) {
                    log += packet
                    if (!drop(name, count++, packet)) queue.addLast(packet)
                }
            }

        private fun listener(onReady: () -> Unit, into: MutableList<Byte>) = object : Gattlink.Listener {
            override fun onSessionReady() = onReady()
            override fun onData(data: ByteArray) {
                into += data.toList()
            }
        }

        fun pump() {
            while (toA.isNotEmpty() || toB.isNotEmpty()) {
                toB.removeFirstOrNull()?.let(b::handlePacket)
                toA.removeFirstOrNull()?.let(a::handlePacket)
            }
        }

        fun run(ms: Long) {
            repeat((ms / 50).toInt()) {
                pump()
                scheduler.advance(50)
            }
            pump()
        }
    }

    @Test
    fun resetHandshakeUsesGoldenGateBytes() {
        val p = Pair()
        p.b.reset() // the band side waits in its own handshake
        p.toA.clear()
        p.a.start()
        assertArrayEquals(byteArrayOf(0x80.toByte()), p.sentByA.first())
        p.run(100)
        assertEquals(Gattlink.State.READY, p.a.state)
        assertEquals(Gattlink.State.READY, p.b.state)
        assertTrue(p.sentByA.any { it.contentEquals(byteArrayOf(0x81.toByte(), 0, 0, 8, 8)) })
    }

    @Test
    fun streamArrivesWholeAndInOrder() {
        val p = Pair()
        p.b.reset()
        p.toA.clear()
        p.a.start()
        p.run(100)
        val data = Random(1).nextBytes(3_000)
        p.a.write(data)
        p.run(2_000)
        assertArrayEquals(data, p.receivedByB.toByteArray())
    }

    @Test
    fun droppedPacketIsResent() {
        // Lose A's 6th packet: a data packet once the handshake is done.
        val p = Pair(drop = { from, index, _ -> from == "a" && index == 5 })
        p.b.reset()
        p.toA.clear()
        p.a.start()
        p.run(100)
        val data = Random(2).nextBytes(500)
        p.a.write(data)
        p.run(10_000)
        assertArrayEquals(data, p.receivedByB.toByteArray())
    }

    @Test
    fun bothDirectionsAtOnce() {
        val p = Pair(mtu = 64)
        p.b.reset()
        p.toA.clear()
        p.a.start()
        p.run(100)
        val ab = Random(3).nextBytes(1_000)
        val ba = Random(4).nextBytes(1_000)
        p.a.write(ab)
        p.b.write(ba)
        p.run(3_000)
        assertArrayEquals(ab, p.receivedByB.toByteArray())
        assertArrayEquals(ba, p.receivedByA.toByteArray())
    }

    @Test
    fun mutedEndAnswersNothing() {
        val p = Pair()
        p.b.reset()
        p.toA.clear()
        p.a.start()
        p.run(100)
        p.a.mute()
        val before = p.sentByA.size
        p.b.reset()
        p.run(5_000)
        assertEquals(before, p.sentByA.size)
    }
}
