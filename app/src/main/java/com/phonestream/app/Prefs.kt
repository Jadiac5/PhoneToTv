package com.phonestream.app

import android.content.Context
import com.phonestream.app.core.AspectMode
import com.phonestream.app.core.Preset
import com.phonestream.app.update.ReleaseInfo

/** Tiny SharedPreferences wrapper for the few things worth remembering. */
object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("phonestream", Context.MODE_PRIVATE)

    const val MODE_SEND = "send"
    const val MODE_RECEIVE = "receive"

    fun lastMode(c: Context): String? = sp(c).getString("mode", null)
    fun setLastMode(c: Context, v: String) = sp(c).edit().putString("mode", v).apply()

    fun preset(c: Context): Preset = Preset.fromName(sp(c).getString("preset", null))
    fun setPreset(c: Context, p: Preset) = sp(c).edit().putString("preset", p.name).apply()

    fun aspect(c: Context): AspectMode = AspectMode.fromName(sp(c).getString("aspect", null))
    fun setAspect(c: Context, a: AspectMode) = sp(c).edit().putString("aspect", a.name).apply()

    fun audio(c: Context): Boolean = sp(c).getBoolean("audio", true)
    fun setAudio(c: Context, v: Boolean) = sp(c).edit().putBoolean("audio", v).apply()

    fun lastIp(c: Context): String = sp(c).getString("ip", "") ?: ""
    fun setLastIp(c: Context, v: String) = sp(c).edit().putString("ip", v).apply()

    // ---- phone speaker off while streaming ----

    /** Silence the phone while it streams (the TV plays the sound). */
    fun mutePhone(c: Context): Boolean = sp(c).getBoolean("mutePhone", true)
    fun setMutePhone(c: Context, v: Boolean) = sp(c).edit().putBoolean("mutePhone", v).apply()

    /** Does muting the phone leave the captured sound intact? 0 = not tested yet, 1 = yes, 2 = no (captured sound follows the volume). */
    fun muteSupport(c: Context): Int = sp(c).getInt("muteSupport", 0)
    fun setMuteSupport(c: Context, v: Int) = sp(c).edit().putInt("muteSupport", v).apply()

    /** The media volume to put back after streaming; -1 = the volume is not being held down right now. */
    fun savedVolume(c: Context): Int = sp(c).getInt("savedVolume", -1)

    // commit(), not apply(): this must be on disk before the volume goes down, in case the app dies mid-stream
    fun setSavedVolume(c: Context, v: Int) = sp(c).edit().putInt("savedVolume", v).commit()

    // ---- picture/sound sync ----

    /** Extra delay of the picture on the TV relative to the sound, in ms (for a soundbar or Bluetooth speaker that adds delay). */
    fun syncOffsetMs(c: Context): Int = sp(c).getInt("syncOffsetMs", 0).coerceIn(SYNC_MIN_MS, SYNC_MAX_MS)
    fun setSyncOffsetMs(c: Context, v: Int) = sp(c).edit().putInt("syncOffsetMs", v.coerceIn(SYNC_MIN_MS, SYNC_MAX_MS)).apply()

    const val SYNC_MIN_MS = -200
    const val SYNC_MAX_MS = 600
    const val SYNC_STEP_MS = 20

    // ---- updates ----

    /** Look for a new version when the app starts (at most every few hours). */
    fun autoCheck(c: Context): Boolean = sp(c).getBoolean("autoCheck", true)
    fun setAutoCheck(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoCheck", v).apply()

    fun lastCheckAt(c: Context): Long = sp(c).getLong("updCheckedAt", 0L)

    /** What the last successful check found (null = nothing known / up to date). */
    fun cachedUpdate(c: Context): ReleaseInfo? {
        val p = sp(c)
        val version = p.getString("updVersion", null) ?: return null
        val url = p.getString("updUrl", null) ?: return null
        return ReleaseInfo(
            version, url, p.getLong("updSize", 0L), p.getString("updDigest", null),
            p.getString("updNotes", "") ?: "", p.getString("updPage", "") ?: "",
        )
    }

    fun setCheckResult(c: Context, at: Long, update: ReleaseInfo?) {
        val e = sp(c).edit().putLong("updCheckedAt", at)
        if (update == null) {
            e.remove("updVersion").remove("updUrl").remove("updSize").remove("updDigest").remove("updNotes").remove("updPage")
        } else {
            e.putString("updVersion", update.version).putString("updUrl", update.apkUrl).putLong("updSize", update.size)
                .putString("updDigest", update.sha256).putString("updNotes", update.notes).putString("updPage", update.pageUrl)
        }
        e.apply()
    }
}
