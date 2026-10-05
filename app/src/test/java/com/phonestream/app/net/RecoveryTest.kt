package com.phonestream.app.net

import com.phonestream.app.core.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    private fun forMe(msg: ByteArray, name: String = "WZ-FTS", addresses: List<String> = listOf("192.168.178.93")) =
        HelpRequest.isForMe(msg, msg.size, name, addresses)

    @Test
    fun aHelpRequestReachesTheReceiverItNames() {
        assertTrue(forMe(HelpRequest.encode("WZ-FTS", "192.168.178.93")))
        assertTrue("names match regardless of case", forMe(HelpRequest.encode("wz-fts", "10.9.9.9")))
    }

    @Test
    fun aReceiverTypedInByAddressAnswersToItsAddress() {
        // The phone only knows the address it was given, so the "name" in the message is that address.
        assertTrue(forMe(HelpRequest.encode("192.168.178.93", "192.168.178.93")))
    }

    @Test
    fun otherReceiversStayOutOfIt() {
        assertFalse(forMe(HelpRequest.encode("Bedroom TV", "192.168.178.50")))
        assertFalse("a request for nobody in particular", forMe(HelpRequest.encode("", "")))
    }

    @Test
    fun nothingButAHelpRequestCounts() {
        for (text in listOf(Proto.UDP_PROBE, "${Proto.UDP_REPLY}\nWZ-FTS\n47800", "hello", Proto.UDP_HELP, "${Proto.UDP_HELP}\nWZ-FTS")) {
            assertFalse(text, forMe(text.toByteArray()))
        }
    }

    @Test
    fun aRouterThatStaysSilentTriggersOneResetThenWaits() {
        val p = HealPolicy(cooldownMs = 90_000, routerFailuresNeeded = 3)
        assertFalse(p.onRouterCheck(false, 0))
        assertFalse(p.onRouterCheck(false, 5_000))
        assertTrue("third silent check in a row", p.onRouterCheck(false, 10_000))
        // Still silent right after the reset: give it time instead of resetting again at once.
        for (t in 15_000L..60_000L step 5_000) assertFalse("at $t", p.onRouterCheck(false, t))
    }

    @Test
    fun oneAnswerFromTheRouterClearsTheCount() {
        val p = HealPolicy(routerFailuresNeeded = 3)
        assertFalse(p.onRouterCheck(false, 0))
        assertFalse(p.onRouterCheck(false, 5_000))
        assertFalse(p.onRouterCheck(true, 10_000))
        assertFalse(p.onRouterCheck(false, 15_000))
        assertFalse(p.onRouterCheck(false, 20_000))
        assertTrue(p.onRouterCheck(false, 25_000))
    }

    @Test
    fun anAutomaticResetThatNeverHelpsIsNotRepeatedForever() {
        val p = HealPolicy(cooldownMs = 1_000, routerFailuresNeeded = 1, autoResetsWithoutProgress = 3)
        var resets = 0
        var t = 0L
        repeat(20) {
            t += 2_000
            if (p.onRouterCheck(false, t)) resets++
        }
        assertEquals(3, resets)
        // The router answers again: the budget is back.
        assertFalse(p.onRouterCheck(true, t + 2_000))
        assertTrue(p.onRouterCheck(false, t + 4_000))
    }

    @Test
    fun aPhonesRequestIsHonouredOncePerCooldown() {
        val p = HealPolicy(cooldownMs = 90_000)
        assertTrue(p.onHelpRequest(0))
        assertFalse("three broadcasts, one reset", p.onHelpRequest(100))
        assertFalse(p.onHelpRequest(60_000))
        assertTrue(p.onHelpRequest(91_000))
    }

    @Test
    fun theUsersOwnRequestRestartsTheCooldown() {
        val p = HealPolicy(cooldownMs = 90_000)
        p.onManual(10_000)
        assertFalse("a phone's request right after the user's own fix", p.onHelpRequest(20_000))
        assertTrue(p.onHelpRequest(101_000))
    }

    @Test
    fun theStatusLineSaysWhatTheNetworkIsDoing() {
        val line = NetworkHealth.summary(WifiState(-52, 5180, 433), true, true, 4, "192.168.178.20", null)
        assertEquals("Wi-Fi · 5 GHz · -52 dBm · 433 Mbps  ·  router answers  ·  phone heard just now (192.168.178.20)", line)

        val bad = NetworkHealth.summary(WifiState(-80, 2437, 0), true, false, null, null, "Wi-Fi refreshed 14:03")
        assertEquals("Wi-Fi · 2.4 GHz · -80 dBm  ·  router NOT answering  ·  no phone heard yet  ·  Wi-Fi refreshed 14:03", bad)

        assertTrue(NetworkHealth.summary(null, false, null, null, null, null).startsWith("No network"))
        assertTrue(NetworkHealth.summary(null, true, true, 125, null, null).contains("phone heard 2 min ago"))
    }
}
