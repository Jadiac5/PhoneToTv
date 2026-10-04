package com.phonestream.app.send

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import com.phonestream.app.core.AudioStamp
import com.phonestream.app.core.Msg
import com.phonestream.app.core.Proto

/**
 * Captures what the phone is playing (AudioPlaybackCapture, Android 10+) as raw 48 kHz stereo PCM16 --
 * uncompressed -- in 20 ms chunks and hands it to the [PacketWriter].
 */
class AudioStreamer(
    private val projection: MediaProjection,
    private val writer: PacketWriter,
    private val onFailed: (String) -> Unit,
    /** Loudest sample (0..32768) of every 20 ms chunk, called on the capture thread (see [com.phonestream.app.core.MuteGuard]). */
    private val onPeak: ((Int) -> Unit)? = null,
) {
    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** Creates the recorder. Returns null on success, otherwise a short reason why audio is unavailable. */
    @SuppressLint("MissingPermission") // checked by the caller (SenderSession) before constructing us
    fun prepare(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "needs Android 10 or newer"
        return try {
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Proto.AUDIO_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(
                Proto.AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capture)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, CHUNK_BYTES * 4) * 2)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return "the system refused to capture audio"
            }
            record = rec
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Starts capturing. Call after [prepare] returned null and the receiver knows the audio format. */
    fun begin(): String? {
        val rec = record ?: return "not prepared"
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return "recorder did not start"
        } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        }
        running = true
        thread = Thread({ loop(rec) }, "ps-audio").apply {
            isDaemon = true
            start()
        }
        return null
    }

    private fun loop(rec: AudioRecord) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {
        }
        val buf = ByteArray(CHUNK_BYTES)
        val stamp = AudioStamp(Proto.AUDIO_RATE)
        while (running) {
            var got = 0
            while (got < CHUNK_BYTES && running) {
                val n = rec.read(buf, got, CHUNK_BYTES - got)
                if (n < 0) {
                    if (running) onFailed("Audio capture stopped (error $n)")
                    return
                }
                got += n
            }
            if (got > 0 && running) {
                writer.sendAudio(Msg.audioData(stamp.stamp(System.nanoTime() / 1000, got / FRAME_BYTES), buf, got))
                onPeak?.invoke(peak(buf, got))
            }
        }
    }

    /** Loudest absolute 16-bit little-endian sample in the first [len] bytes. */
    private fun peak(b: ByteArray, len: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < len) {
            val s = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8) // sign comes from the high byte
            val a = if (s < 0) -s else s
            if (a > max) max = a
            i += 2
        }
        return max
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        record = null
    }

    companion object {
        /** 20 ms of 48 kHz stereo 16-bit. */
        const val CHUNK_BYTES = Proto.AUDIO_RATE / 50 * Proto.AUDIO_CHANNELS * 2
        private const val FRAME_BYTES = Proto.AUDIO_CHANNELS * 2
    }
}
