package com.phonestream.app.receive

import com.phonestream.app.core.AudioConfig
import com.phonestream.app.core.AudioData
import com.phonestream.app.core.Stats
import com.phonestream.app.core.VideoConfig
import com.phonestream.app.core.VideoFrame

/** Where [ReceiverServer] delivers what arrives from the sender (the real ones decode and play it). */
interface VideoSink {
    /** A new video format, or null when the stream ended. */
    fun configure(cfg: VideoConfig?)

    /** Must not block: it runs on the network thread. */
    fun feed(f: VideoFrame)

    /** How playback is coping since the last call; the sender uses it to ease off. Null: nothing to report. */
    fun takeStats(): Stats? = null
}

interface AudioSink {
    fun start(cfg: AudioConfig)
    fun feed(d: AudioData)
    fun stop()
}
