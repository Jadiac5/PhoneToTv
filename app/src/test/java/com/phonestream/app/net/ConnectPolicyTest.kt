package com.phonestream.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

class ConnectPolicyTest {
    private val unreachable = ConnectException(
        "failed to connect to /192.168.178.93 (port 47800) from /:: (port 41234) after 4000ms: connect failed: EHOSTUNREACH (No route to host)"
    )
    private val refused = ConnectException(
        "failed to connect to /192.168.178.93 (port 47800) after 4000ms: isConnected failed: ECONNREFUSED (Connection refused)"
    )

    @Test
    fun nothingAnsweringIsWorthAnotherTry() {
        assertTrue(ConnectPolicy.retryable(unreachable))
        assertTrue(ConnectPolicy.retryable(refused))
        assertTrue(ConnectPolicy.retryable(NoRouteToHostException("x")))
        assertTrue(ConnectPolicy.retryable(SocketTimeoutException("x")))
        assertTrue(ConnectPolicy.retryable(NoAnswerException("accepted but silent")))
    }

    @Test
    fun anAnswerIsFinal() {
        assertFalse(ConnectPolicy.retryable(IOException("TV runs an older PhoneStream")))
        assertFalse(ConnectPolicy.retryable(IOException("The receiver is busy")))
        assertFalse(ConnectPolicy.retryable(IllegalStateException("bug")))
    }

    @Test
    fun theOsReasonIsPulledOutOfTheMessage() {
        assertEquals("EHOSTUNREACH (No route to host)", ConnectPolicy.reason(unreachable))
        assertEquals("ECONNREFUSED (Connection refused)", ConnectPolicy.reason(refused))
        assertNull(ConnectPolicy.reason(ConnectException("something odd")))
        assertNull(ConnectPolicy.reason(ConnectException()))
    }

    @Test
    fun theRetryScheduleStartsAtOnceThenBacksOff() {
        assertEquals(0L, ConnectPolicy.pauseBeforeMs(0))
        var previous = -1L
        for (i in 0 until ConnectPolicy.ATTEMPTS) {
            val p = ConnectPolicy.pauseBeforeMs(i)
            assertTrue("pause $i must not shrink", p >= previous)
            previous = p
        }
        // Whole worst case (every attempt times out, plus the pauses) stays well under half a minute.
        val worst = (0 until ConnectPolicy.ATTEMPTS).sumOf { ConnectPolicy.pauseBeforeMs(it) + ConnectPolicy.CONNECT_TIMEOUT_MS }
        assertTrue("worst case $worst ms", worst < 30_000)
        assertEquals(ConnectPolicy.pauseBeforeMs(ConnectPolicy.ATTEMPTS - 1), ConnectPolicy.pauseBeforeMs(99))
    }

    @Test
    fun noRouteGetsANetworkExplanationNotAReceiveModeHint() {
        val text = ConnectPolicy.explain("WZ-FTS", "192.168.179.93", 47800, unreachable, 4)
        assertTrue(text, "EHOSTUNREACH (No route to host)" in text)
        assertTrue(text, "192.168.179.93:47800" in text)
        assertTrue(text, "same Wi-Fi" in text && "guest" in text)
        assertTrue(text, "tried 4 times" in text)
        assertTrue(text, "Enter IP address manually" in text)
        assertFalse(text, "Receive mode" in text)
    }

    @Test
    fun refusedMeansNothingListensThere() {
        val text = ConnectPolicy.explain("WZ-FTS", "192.168.178.93", 47800, refused, 1)
        assertTrue(text, "ECONNREFUSED (Connection refused)" in text)
        assertTrue(text, "Receive mode" in text)
        assertFalse(text, "tried" in text)
    }

    @Test
    fun aSilentReceiverKeepsItsOwnMessage() {
        val text = ConnectPolicy.explain("TV", "10.0.0.2", 47800, NoAnswerException("TV accepted the connection but did not reply."), 2)
        assertTrue(text, text.startsWith("TV accepted the connection but did not reply."))
        assertTrue(text, "tried 2 times" in text)
    }
}
