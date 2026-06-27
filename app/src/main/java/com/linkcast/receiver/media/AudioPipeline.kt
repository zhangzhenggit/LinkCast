package com.linkcast.receiver.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.autoservice.carplay.CarplayNative
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AudioPipeline {
    private val outputStreams = ConcurrentHashMap<String, AudioOutput>()
    private val inputStreams = ConcurrentHashMap<String, AudioInput>()

    // Native calls onAudioStreamCreate(packed, audioType, nativePtr):
    //   packed: [samplingRate:16][streamType:8][dir:4][channel:4]  (per CarplayNative smali)
    //   audioType: "media"/"default"/"telephony"/"alert"/"speechRecognition"
    //   nativePtr: hex string — the pointer passed to readAudioData()/sendAudioData()
    // Audio PCM is pulled from the native via readAudioData(ptr); the earlier version
    // mis-read all three args (treated packed as dir, parsed ptr from the wrong string),
    // so it polled readAudioData(0) and got nothing → silence.
    fun create(packed: Int, audioType: String, nativePtr: String) {
        val samplingRate = ((packed ushr 16) and 0xffff).let { if (it in 8000..192000) it else 44100 }
        val dir = (packed ushr 4) and 0xf
        val channels = (packed and 0xf).let { if (it == 1 || it == 2) it else 2 }
        // The pointer is an UNSIGNED 64-bit value; Kotlin toLong(16) returns null for
        // values above Long.MAX (e.g. 0xb4...) → ptr=0 → readAudioData(0) null-deref
        // crash (fault 0xb0). Parse it unsigned. This is the native audio handle for
        // readAudioData(ptr) (matches carplay.a, which plays via AudioTrack + polling).
        val ptr = runCatching {
            java.lang.Long.parseUnsignedLong(nativePtr.removePrefix("0x").trim(), 16)
        }.getOrDefault(0L)
        Log.d("AudioPipeline", "create type=$audioType dir=$dir rate=$samplingRate ch=$channels ptr=0x${ptr.toString(16)}")
        // dir bit0 = output (playback): the native decodes AAC→PCM and exposes it via
        // readAudioData(ptr); pull it and write to an AudioTrack. Key streams by nativePtr.
        if (dir and 0x1 != 0 && ptr != 0L) {
            outputStreams[nativePtr] = AudioOutput(AudioSpec(samplingRate, channels), ptr).also { it.start() }
        }
    }

    fun destroy(key: String) {
        outputStreams.remove(key)?.stop()
        inputStreams.remove(key)?.stop()
    }

    fun release() {
        outputStreams.keys.toList().forEach(::destroy)
        inputStreams.keys.toList().forEach(::destroy)
    }
}

data class AudioSpec(val sampleRate: Int = 44100, val channels: Int = 2) {
    companion object {
        fun parse(raw: String): AudioSpec {
            val numbers = Regex("\\d+").findAll(raw).map { it.value.toInt() }.toList()
            val rate = numbers.firstOrNull { it in 8000..192000 } ?: 44100
            val channels = numbers.firstOrNull { it == 1 || it == 2 } ?: 2
            return AudioSpec(rate, channels)
        }
    }

    val outputChannelMask: Int = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    val inputChannelMask: Int = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
}

private class AudioOutput(private val spec: AudioSpec, private val nativePointer: Long) {
    private var track: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        val minSize = AudioTrack.getMinBufferSize(spec.sampleRate, spec.outputChannelMask, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(spec.sampleRate).setChannelMask(spec.outputChannelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes((minSize * 2).coerceAtLeast(4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        running.set(true)
        thread = Thread({ playLoop() }, "linkcast-audio-play").also { it.start() }
    }

    // The native decodes the AirPlay audio and exposes PCM via readAudioData(ptr): it
    // returns a buffer of [12-byte header][PCM]; header[0..1]==1 means flush. Poll it and
    // write the PCM (offset 12, length total-12) to the AudioTrack. (Matches carplay.a.)
    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            while (running.get()) {
                val data = runCatching { CarplayNative.readAudioData(nativePointer) }.getOrNull()
                if (data == null || data.size <= HEADER) {
                    SystemClock.sleep(2L)
                    continue
                }
                val flush = ((data[0].toInt() and 0xff) shl 8 or (data[1].toInt() and 0xff)) == 1
                if (flush) {
                    runCatching { track?.flush() }
                    continue
                }
                runCatching { track?.write(data, HEADER, data.size - HEADER) }
            }
        } catch (error: Exception) {
            Log.w("AudioPipeline", "audio play loop failed", error)
        }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread?.join(100L)
        thread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    companion object { private const val HEADER = 12 }
}

private class AudioInput(private val spec: AudioSpec, key: String) {
    private val running = AtomicBoolean(false)
    private val nativePointer = key.removePrefix("0x").toLongOrNull(16) ?: 0L
    private var thread: Thread? = null

    fun start() {
        running.set(true)
        thread = Thread({ recordLoop() }, "linkcast-audio-record").also { it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread?.join(100L)
        thread = null
    }

    private fun recordLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frameBytes = (spec.sampleRate * 2 * spec.channels) / 50
        val minSize = AudioRecord.getMinBufferSize(spec.sampleRate, spec.inputChannelMask, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            spec.sampleRate,
            spec.inputChannelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            (minSize * 2).coerceAtLeast(frameBytes * 2)
        )
        try {
            val buffer = ByteArray(frameBytes)
            recorder.startRecording()
            repeat(3) { CarplayNative.sendAudioData(buffer, buffer.size, 2.5f, nativePointer) }
            while (running.get()) {
                var offset = 0
                while (offset < buffer.size && running.get()) {
                    val read = recorder.read(buffer, offset, buffer.size - offset)
                    if (read > 0) offset += read else SystemClock.sleep(100L)
                }
                if (offset > 0 && running.get()) {
                    CarplayNative.sendAudioData(buffer, offset, 2.5f, nativePointer)
                }
            }
        } catch (error: Exception) {
            Log.w("AudioPipeline", "AudioRecord failed", error)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}
