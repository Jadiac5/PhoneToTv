package com.phonestream.app.net

import com.phonestream.app.core.Proto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class ReceiverLocatorTest {
    private val lo: InetAddress = InetAddress.getByName("127.0.0.1")
    private val sockets = ArrayList<DatagramSocket>()

    @After
    fun tearDown() {
        sockets.forEach { it.close() }
    }

    /** A stand-in for a receiver's UDP responder; [dropFirst] probes are ignored to look like lost datagrams. */
    private fun responder(name: String, advertisedPort: Int = 47800, dropFirst: Int = 0, hits: AtomicInteger = AtomicInteger()): DatagramSocket {
        val s = DatagramSocket(0, lo)
        sockets += s
        Thread({
            val buf = ByteArray(256)
            try {
                while (!s.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (String(p.data, 0, p.length, Charsets.UTF_8).trim() != Proto.UDP_PROBE) continue
                    if (hits.incrementAndGet() <= dropFirst) continue
                    val reply = "${Proto.UDP_REPLY}\n$name\n$advertisedPort".toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                }
            } catch (_: Exception) {
            }
        }, "test-responder").apply { isDaemon = true; start() }
        return s
    }

    @Test
    fun aReplyBecomesAReceiverAtTheAddressItCameFrom() {
        val bytes = "${Proto.UDP_REPLY}\nWZ-FTS\n47800".toByteArray()
        val r = ReceiverLocator.parseReply(bytes, bytes.size, InetAddress.getByName("192.168.178.93"))
        assertEquals(Receiver("WZ-FTS", "192.168.178.93", 47800), r)
    }

    @Test
    fun anythingElseIsNotAReply() {
        val from = InetAddress.getByName("192.168.178.93")
        for (text in listOf(Proto.UDP_PROBE, "hello", "${Proto.UDP_REPLY}\nonly-a-name", "${Proto.UDP_REPLY}\nTV\nnot-a-port")) {
            val b = text.toByteArray()
            assertNull(text, ReceiverLocator.parseReply(b, b.size, from))
        }
        val b = "${Proto.UDP_REPLY}\nTV\n47800".toByteArray()
        assertNull("IPv6 sources are ignored", ReceiverLocator.parseReply(b, b.size, InetAddress.getByName("::1")))
    }

    @Test
    fun findsAReceiverByName() {
        val r = responder("WZ-FTS")
        val found = ReceiverLocator.find("wz-fts", targets = listOf(lo), port = r.localPort, waitMs = 800)
        assertEquals(listOf(Receiver("WZ-FTS", "127.0.0.1", 47800)), found)
    }

    @Test
    fun otherNamesAreLeftOut() {
        val r = responder("Bedroom TV")
        val found = ReceiverLocator.find("WZ-FTS", targets = listOf(lo), port = r.localPort, waitMs = 500)
        assertTrue(found.toString(), found.isEmpty())
        val all = ReceiverLocator.find(null, targets = listOf(lo), port = r.localPort, waitMs = 500)
        assertEquals(1, all.size)
    }

    @Test
    fun aKnownHostIsAskedDirectly() {
        val r = responder("WZ-FTS")
        val found = ReceiverLocator.find("WZ-FTS", knownHosts = listOf("127.0.0.1"), targets = emptyList(), port = r.localPort, waitMs = 800)
        assertEquals(1, found.size)
    }

    @Test
    fun theSameAnswerTwiceIsOneReceiver() {
        val r = responder("WZ-FTS")
        // Asked via the broadcast list and as a known host: two probes, two replies, one entry.
        val found = ReceiverLocator.find("WZ-FTS", knownHosts = listOf("127.0.0.1"), targets = listOf(lo), port = r.localPort, waitMs = 800)
        assertEquals(1, found.size)
    }

    @Test
    fun aLostProbeIsAskedAgain() {
        val hits = AtomicInteger()
        val r = responder("WZ-FTS", dropFirst = 1, hits = hits)
        val found = ReceiverLocator.find("WZ-FTS", targets = listOf(lo), port = r.localPort, waitMs = 1200)
        assertEquals(1, found.size)
        assertTrue("probed ${hits.get()} times", hits.get() >= 2)
    }

    @Test
    fun silenceReturnsEmptyOnTime() {
        val quiet = DatagramSocket(0, lo).also { sockets += it } // nobody reads or answers
        val t0 = System.nanoTime()
        val found = ReceiverLocator.find("WZ-FTS", targets = listOf(lo), port = quiet.localPort, waitMs = 400)
        val took = (System.nanoTime() - t0) / 1_000_000
        assertTrue(found.isEmpty())
        assertTrue("took $took ms", took in 350..1500)
    }
}
