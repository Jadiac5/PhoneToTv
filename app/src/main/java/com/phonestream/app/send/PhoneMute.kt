package com.phonestream.app.send

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import com.phonestream.app.Prefs

/**
 * Holds the phone's media volume at zero while it streams (the TV plays the sound), and puts it back afterwards.
 *
 * The volume to restore is written to [Prefs] BEFORE it is lowered, so even if the app is killed mid-stream the
 * next start ([restoreLeftover]) can give the user their volume back. While holding, a volume-key press (or anything
 * else raising the volume) is undone straight away.
 */
class PhoneMute(private val ctx: Context, private val handler: Handler) {
    private val audio = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var holding = false
    private var saved = -1
    private var registered = false

    @get:Synchronized
    val isMuted: Boolean get() = holding

    private val watcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            synchronized(this@PhoneMute) {
                if (holding && volume() > 0) lower()
            }
        }
    }

    /** Silences (true) or restores (false) the phone. Safe to call repeatedly, from any thread. */
    @Synchronized
    fun setMuted(on: Boolean) {
        if (on) hold() else release(stopWatching = false)
    }

    /** Restores the volume and stops watching it. */
    @Synchronized
    fun close() {
        release(stopWatching = true)
    }

    private fun hold() {
        if (holding) return
        val current = volume()
        val leftover = Prefs.savedVolume(ctx)
        saved = when {
            current > 0 -> current
            leftover >= 0 -> leftover // an earlier run that died left the volume down: that is the user's volume
            else -> -1 // the phone was silent to begin with: nothing to put back
        }
        if (current > 0) Prefs.setSavedVolume(ctx, current)
        holding = true
        lower()
        if (!registered) {
            registered = try {
                val filter = IntentFilter(VOLUME_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(watcher, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
                else ctx.registerReceiver(watcher, filter, null, handler)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun release(stopWatching: Boolean) {
        val wasHolding = holding
        holding = false
        if (stopWatching && registered) {
            try { ctx.unregisterReceiver(watcher) } catch (_: Exception) {}
            registered = false
        }
        if (!wasHolding) return
        if (saved >= 0) {
            // Only put the volume back if nothing else changed it meanwhile (the user's own change wins).
            if (volume() == 0) setVolume(saved)
            Prefs.setSavedVolume(ctx, -1)
        }
        saved = -1
    }

    private fun volume(): Int = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { 0 }

    private fun lower() = setVolume(0)

    private fun setVolume(v: Int) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) // flags = 0: no volume panel pops up
        } catch (_: Exception) {
            // some devices refuse (e.g. certain restricted profiles): the phone just stays audible
        }
    }

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        /**
         * If a previous run was killed while the volume was held down, puts it back. Call at app start, but never
         * while a stream is running (it would unmute the phone under the stream).
         */
        fun restoreLeftover(ctx: Context) {
            val saved = Prefs.savedVolume(ctx)
            if (saved < 0 || StreamState.snapshot.phase != Phase.IDLE) return
            try {
                val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
                }
            } catch (_: Exception) {
            }
            Prefs.setSavedVolume(ctx, -1)
        }
    }
}
