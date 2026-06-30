package com.linkcast.receiver.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.google.android.projection.common.BufferPool
import com.linkcast.receiver.diag.LinkLog
import java.nio.ByteBuffer

/**
 * HEVC/H.264 解码管线。
 *
 * 解码器始终有一个有效的输出 Surface:有屏上 Surface 时输出到它,没有(如 App 切后台、
 * SurfaceView 销毁)时切到一个常驻占位 Surface。这样解码器全程不释放、持续消费帧流,
 * 上层 native 不会因输出端消失而停流,回前台只需把输出切回屏上 Surface 即可瞬时恢复。
 */
class VideoPipeline {
    private val thread = HandlerThread("linkcast-video").also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile private var mime = MIME_HEVC
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var width = 1280
    @Volatile private var height = 720
    @Volatile private var frameCount = 0L

    // 屏上 Surface(来自 SurfaceView);为空表示当前无可见输出。
    @Volatile private var realSurface: Surface? = null

    // 常驻占位 Surface:无屏上 Surface 时解码器输出到这里,保持解码器存活、帧流不断。
    private var dummyTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    // 解码器(重新)挂到屏上 Surface 后回调,用于请求一个关键帧,使新画面尽快刷新。
    @Volatile var onDecoderReady: (() -> Unit)? = null

    // 解码器致命失败(创建失败或解码抛 CodecException)回调,参数为失败时是否为 HEVC。
    // 上层据此做码型回退;同一会话只回调一次。
    @Volatile var onDecoderFatal: ((wasHevc: Boolean) -> Unit)? = null
    @Volatile private var fatalReported = false

    // 设置使用的视频码型(由协商决策传入,与发送端一致)。true=HEVC,false=H.264。
    fun setCodec(hevc: Boolean) {
        handler.post {
            val newMime = if (hevc) MIME_HEVC else MIME_AVC
            if (newMime != mime) {
                mime = newMime
                fatalReported = false
                if (codec != null) rebuildDecoder()
            }
        }
    }

    fun setSurface(surface: Surface?, width: Int, height: Int) {
        handler.post {
            realSurface = surface
            val target = outputSurface()
            val current = codec
            if (current == null) {
                // 解码器尚未建立:建好后若已有屏上 Surface,请求关键帧刷新。
                if (rebuildDecoder() != null && surface != null) onDecoderReady?.invoke()
                return@post
            }
            // 解码器已存在:热切换输出 Surface,不重建,保持帧流连续。
            val swapped = runCatching { current.setOutputSurface(target) }.isSuccess
            if (!swapped) {
                LinkLog.w(TAG) { "切换输出 Surface 失败,改为重建解码器" }
                if (rebuildDecoder() == null) return@post
            }
            // 切回屏上 Surface 时请求关键帧,清掉切换瞬间可能的花屏。
            if (surface != null) onDecoderReady?.invoke()
        }
    }

    /** 设置编码视频流尺寸(来自 CarPlay 协商,如 1280x720)。 */
    fun setVideoSize(w: Int, h: Int) {
        handler.post {
            if (w in 1..7680 && h in 1..4320 && (w != width || h != height)) {
                width = w
                height = h
                if (codec != null) rebuildDecoder()
            }
        }
    }

    fun queueFrame(flags: Int, buffer: ByteBuffer) {
        handler.post {
            try {
                frameCount++
                if (frameCount % 60 == 1L) {
                    LinkLog.d(TAG) { "视频帧 #$frameCount keyFrame=${(flags and 0x8000) != 0}" }
                }
                val decoder = codec ?: rebuildDecoder()
                if (decoder == null) {
                    LinkLog.w(TAG) { "无解码器,丢弃帧 #$frameCount" }
                    BufferPool.returnBuffer(buffer)
                    return@post
                }
                val inputIndex = decoder.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val input = decoder.getInputBuffer(inputIndex)
                    input?.clear()
                    // native 送来的是长度前缀(4 字节大端)的 NAL 单元,MediaCodec 需要 Annex-B
                    // 起始码;把每段长度原位改写成 00 00 00 01(同为 4 字节,长度不变)。
                    val size = toAnnexB(buffer)
                    input?.put(buffer)
                    decoder.queueInputBuffer(inputIndex, 0, size, 0L, mediaCodecFlags(flags))
                }
                drainOutput(decoder)
            } catch (error: MediaCodec.CodecException) {
                // 解码器无法解当前码流(常见于车机号称支持 HEVC 实际解不动)→ 上报致命失败,由上层回退码型。
                LinkLog.w(TAG) { "解码异常($mime): $error" }
                reportFatal()
            } catch (error: Exception) {
                LinkLog.w(TAG) { "解码帧失败,重建解码器: $error" }
                rebuildDecoder()
            } finally {
                BufferPool.returnBuffer(buffer)
            }
        }
    }

    /**
     * 断开连接时调用:停止解码,释放解码器并复位计数。
     * 屏上残留的最后一帧由界面侧隐藏 SurfaceView 来遮掉(不在此涂黑,避免 Surface 在 Canvas/codec
     * 两种生产者模式间切换导致重连时无法重新挂载解码器)。下次有帧到来会自动重建解码器。
     */
    fun clear() {
        handler.post {
            releaseDecoder()
            frameCount = 0
            fatalReported = false
        }
    }

    fun release() {
        handler.post {
            releaseDecoder()
            dummySurface?.release()
            dummySurface = null
            dummyTexture?.release()
            dummyTexture = null
            thread.quitSafely()
        }
    }

    // 当前应使用的输出 Surface:优先屏上 Surface,否则占位 Surface。
    private fun outputSurface(): Surface = realSurface ?: ensureDummySurface()

    private fun ensureDummySurface(): Surface {
        dummySurface?.let { return it }
        // 脱离 GL 上下文的 SurfaceTexture,仅作解码输出的丢弃式接收端(不消费即覆盖)。
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(1, 1) }
        val surface = Surface(texture)
        dummyTexture = texture
        dummySurface = surface
        return surface
    }

    private fun rebuildDecoder(): MediaCodec? {
        releaseDecoder()
        // 码型由协商决定,解码器必须与之一致,故不在本地盲目互换;建不出即上报致命失败,由上层回退码型并重连。
        val decoder = createDecoder(mime, outputSurface())
        if (decoder == null) reportFatal()
        return decoder
    }

    private fun reportFatal() {
        if (fatalReported) return
        fatalReported = true
        onDecoderFatal?.invoke(mime == MIME_HEVC)
    }

    private fun createDecoder(mime: String, surface: Surface): MediaCodec? = runCatching {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, surface, null, 0)
        decoder.start()
        codec = decoder
        LinkLog.d(TAG) { "解码器启动: $mime ${width}x${height}" }
        decoder
    }.onFailure { e -> LinkLog.w(TAG) { "创建 $mime 解码器失败: $e" } }.getOrNull()

    private fun releaseDecoder() {
        val old = codec
        codec = null
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

    // 原位转换:每个 NAL 单元是 [4 字节大端长度][数据],把长度改写成 Annex-B 起始码
    // 00 00 00 01(同为 4 字节,长度保持不变)。返回总字节数;遇到不符合预期的结构则原样返回。
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

    companion object {
        private const val TAG = "VideoPipeline"
        private const val MIME_HEVC = "video/hevc"
        private const val MIME_AVC = "video/avc"
    }
}
