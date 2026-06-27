package com.linkcast.receiver.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.google.android.projection.common.BufferPool
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class VideoPipeline {
    companion object {
        private const val TAG = "VideoPipeline"
        private const val MIME_HEVC = "video/hevc"
        private const val MIME_AVC = "video/avc"
    }

    // CarPlay on current iPhones streams H.265/HEVC; older/fallback is H.264. Default to
    // HEVC and fall back to AVC if HEVC decoding can't be created.
    @Volatile private var mime = MIME_HEVC

    private val thread = HandlerThread("linkcast-video").also { it.start() }
    private val handler = Handler(thread.looper)
    private val configured = AtomicBoolean(false)

    @Volatile private var surface: Surface? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var width = 1280
    @Volatile private var height = 720

    fun setSurface(surface: Surface?, width: Int, height: Int) {
        handler.post {
            this.surface = surface
            // NOTE: do NOT set width/height from the SurfaceView size — those are the
            // DISPLAY dimensions (e.g. full-screen 3840x2264). The decoder must be
            // configured with the VIDEO stream size (1280x720); MediaCodec scales to the
            // surface automatically and adapts to the real size via OUTPUT_FORMAT_CHANGED.
            // Build the decoder lazily on the first frame; only rebuild on surface swap.
            if (codec != null) rebuildDecoder()
        }
    }

    /** Set the encoded video stream dimensions (from CarPlay negotiation, e.g. 1280x720). */
    fun setVideoSize(w: Int, h: Int) {
        handler.post {
            if (w in 1..7680 && h in 1..4320 && (w != width || h != height)) {
                width = w
                height = h
                if (codec != null) rebuildDecoder()
            }
        }
    }

    @Volatile private var frameCount = 0L

    fun queueFrame(flags: Int, buffer: ByteBuffer) {
        handler.post {
            try {
                if (frameCount == 0L) {
                    Log.d(TAG, "First video frame: ${buffer.remaining()}B flags=$flags surface=${surface != null}")
                }
                frameCount++
                val decoder = codec ?: rebuildDecoder()
                if (decoder == null) {
                    if (frameCount % 60 == 1L) Log.w(TAG, "No decoder (surface=${surface != null}); dropping frame #$frameCount")
                    BufferPool.returnBuffer(buffer)
                    return@post
                }
                val inputIndex = decoder.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val input = decoder.getInputBuffer(inputIndex)
                    input?.clear()
                    // The native delivers length-prefixed NAL units (4-byte big-endian
                    // length per the stream's nalSizeHeader=4); MediaCodec wants Annex-B
                    // start codes. Rewrite each 4-byte length to 00 00 00 01 (same size).
                    val size = toAnnexB(buffer)
                    input?.put(buffer)
                    decoder.queueInputBuffer(inputIndex, 0, size, 0L, mediaCodecFlags(flags))
                }
                drainOutput(decoder)
            } catch (error: Exception) {
                Log.w(TAG, "queueFrame failed; rebuilding decoder", error)
                rebuildDecoder()
            } finally {
                BufferPool.returnBuffer(buffer)
            }
        }
    }

    fun release() {
        handler.post {
            releaseDecoder()
            thread.quitSafely()
        }
    }

    private fun rebuildDecoder(): MediaCodec? {
        releaseDecoder()
        val outputSurface = surface ?: return null
        return try {
            val format = MediaFormat.createVideoFormat(mime, width, height)
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, outputSurface, null, 0)
            decoder.start()
            codec = decoder
            configured.set(true)
            Log.d(TAG, "Decoder started: $mime ${width}x${height}")
            decoder
        } catch (error: Exception) {
            Log.w(TAG, "Unable to create $mime decoder", error)
            // Fall back to the other common CarPlay codec and retry once.
            mime = if (mime == MIME_HEVC) MIME_AVC else MIME_HEVC
            codec = null
            configured.set(false)
            runCatching {
                val format = MediaFormat.createVideoFormat(mime, width, height)
                val decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, outputSurface, null, 0)
                decoder.start()
                codec = decoder
                configured.set(true)
                Log.d(TAG, "Decoder started (fallback): $mime ${width}x${height}")
                decoder
            }.getOrNull()
        }
    }

    private fun releaseDecoder() {
        val old = codec
        codec = null
        configured.set(false)
        runCatching { old?.stop() }
        runCatching { old?.release() }
    }

    private fun drainOutput(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = decoder.dequeueOutputBuffer(info, 0L)
            if (outputIndex < 0) return
            decoder.releaseOutputBuffer(outputIndex, info.size > 0)
        }
    }

    // Convert in place: each NAL unit is [4-byte big-endian length][payload]; replace the
    // length with the Annex-B start code 00 00 00 01 (same 4 bytes, so length is preserved).
    // Returns the total byte count; on any inconsistency leaves the buffer untouched.
    private fun toAnnexB(buffer: ByteBuffer): Int {
        val total = buffer.remaining()
        val base = buffer.position()
        var off = base
        val end = base + total
        while (off + 4 <= end) {
            val len = ((buffer.get(off).toInt() and 0xff) shl 24) or
                ((buffer.get(off + 1).toInt() and 0xff) shl 16) or
                ((buffer.get(off + 2).toInt() and 0xff) shl 8) or
                (buffer.get(off + 3).toInt() and 0xff)
            if (len <= 0 || off + 4 + len > end) {
                // Not length-prefixed as expected — feed as-is rather than corrupt it.
                return total
            }
            buffer.put(off, 0)
            buffer.put(off + 1, 0)
            buffer.put(off + 2, 0)
            buffer.put(off + 3, 1)
            off += 4 + len
        }
        return total
    }

    private fun mediaCodecFlags(nativeFlags: Int): Int {
        val keyFrame = (nativeFlags and 0x8000) != 0
        return if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
    }
}
