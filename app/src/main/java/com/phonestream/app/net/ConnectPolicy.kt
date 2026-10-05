package com.phonestream.app.net

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** The receiver (or something at its address) did not answer in time: worth another try. */
class NoAnswerException(message: String) : IOException(message)

/**
 * How the sender keeps trying to reach a receiver that does not answer at first. A TV on Wi-Fi can be slow to
 * wake its radio, or have a new address since the phone last heard of it, so one failed connect is not the end:
 * try again a few times, ask the network where the receiver is now, and only then give up.
 */
object ConnectPolicy {
    const val ATTEMPTS = 5
    const val CONNECT_TIMEOUT_MS = 3000

    // The later pauses leave a receiver that was just asked to refresh its Wi-Fi time to come back.
    private val PAUSE_MS = longArrayOf(0, 1000, 2000, 4000, 6000)

    /** How long to wait before attempt [attempt] (0 = the first one). */
    fun pauseBeforeMs(attempt: Int): Long = PAUSE_MS[attempt.coerceIn(0, PAUSE_MS.size - 1)]

    /** True for "nothing answered" failures that a later try, or another address, can fix. Answers (wrong version, refusal) are final. */
    fun retryable(e: Throwable): Boolean =
        e is ConnectException || e is NoRouteToHostException || e is SocketTimeoutException || e is NoAnswerException

    /** The OS reason in a connect failure ("EHOSTUNREACH (No route to host)"), if the message carries one. */
    fun reason(e: Throwable): String? =
        e.message?.substringAfterLast(": ")?.trim()?.takeIf { it.startsWith("E") && it.contains('(') }

    /** True when the network itself says the receiver is not there (no route, no answer), as opposed to "nothing listens" or a refusal. */
    fun unreachable(e: Throwable): Boolean {
        val why = reason(e)
        return e is NoRouteToHostException || e is SocketTimeoutException || e is NoAnswerException ||
            why?.startsWith("EHOSTUNREACH") == true || why?.startsWith("ENETUNREACH") == true || why?.startsWith("ETIMEDOUT") == true
    }

    /** What to tell the user once every attempt has failed. [askedForHelp]: the receiver was asked to refresh its Wi-Fi meanwhile. */
    fun explain(name: String, host: String, port: Int, e: Throwable, tries: Int, askedForHelp: Boolean = false): String {
        val times = if (tries > 1) " (tried $tries times)" else ""
        val help = if (askedForHelp) " PhoneStream also asked $name to refresh its Wi-Fi connection; if it still fails, choose “Fix connection” on $name." else ""
        if (e is NoAnswerException) return e.message.orEmpty() + times + help
        val why = reason(e)
        return when {
            e is NoRouteToHostException || why?.startsWith("EHOSTUNREACH") == true || why?.startsWith("ENETUNREACH") == true ->
                "$name at $host:$port does not answer on the network (${why ?: "no route to host"})$times. " +
                    "Is this phone on the same Wi-Fi as $name, not a guest network? " +
                    "If $name shows a different address on its screen, use “Enter IP address manually”.$help"
            e is ConnectException ->
                "Can't reach $name at $host:$port${why?.let { " ($it)" } ?: ""}$times. Is PhoneStream open on it in Receive mode?"
            e is SocketTimeoutException -> "$name did not answer$times"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
