package com.phonestream.app.send

import android.os.Handler
import android.os.Looper
import com.phonestream.app.core.AspectMode
import com.phonestream.app.core.Preset
import java.util.concurrent.CopyOnWriteArrayList

enum class Phase { IDLE, CONNECTING, STREAMING }

/** Everything the Send screen needs to show about the running stream. */
data class StreamSnapshot(
    val phase: Phase = Phase.IDLE,
    val receiverName: String = "",
    val status: String = "",
    /** Why the last stream ended (null = ended normally / never ran). */
    val error: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val codec: String = "",
    val mbps: Float = 0f,
    val dropped: Int = 0,
    val audioOn: Boolean = false,
    val audioNote: String = "",
    val preset: Preset = Preset.NATIVE,
    val nativeW: Int = 0,
    val nativeH: Int = 0,
    /** Frames were recently dropped because the network could not keep up. */
    val congested: Boolean = false,
    /** The 16:9 mode that was asked for, and whether it is really in effect (only for landscape screens that aren't 16:9). */
    val aspect: AspectMode = AspectMode.ORIGINAL,
    val reshaped: Boolean = false,
    /** Why the asked-for aspect mode can't be used (empty = no problem). */
    val aspectNote: String = "",
    /** Is the phone's own speaker switched off right now, is that wanted, and what to tell the user if it isn't working. */
    val phoneMuted: Boolean = false,
    val muteWanted: Boolean = true,
    val muteNote: String = "",
)

/** Process-wide stream state: written by the service/session threads, observed on the main thread. */
object StreamState {
    @Volatile
    var snapshot = StreamSnapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(StreamSnapshot) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun update(change: (StreamSnapshot) -> StreamSnapshot) {
        snapshot = change(snapshot)
        val s = snapshot
        main.post { for (l in listeners) l(s) }
    }

    /** Call from the main thread. Immediately delivers the current snapshot. */
    fun addListener(l: (StreamSnapshot) -> Unit) {
        listeners += l
        l(snapshot)
    }

    fun removeListener(l: (StreamSnapshot) -> Unit) {
        listeners -= l
    }
}
