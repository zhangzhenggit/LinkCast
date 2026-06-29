package com.linkcast.receiver.media

import android.media.MediaCodecList
import com.linkcast.receiver.diag.LinkLog

/**
 * 查询本机可用的视频解码器能力(读 MediaCodecList,纯内存查询)。
 *
 * 是否支持某编码是设备的固定属性,进程内缓存一次即可,不持久化:刷机/系统升级后以实时查询为准。
 */
object CodecSupport {
    const val MIME_HEVC = "video/hevc"
    const val MIME_AVC = "video/avc"

    @Volatile private var hevc: Boolean? = null

    /** 本机是否有 HEVC 解码器。 */
    fun hevcDecoderAvailable(): Boolean = hevc ?: hasDecoder(MIME_HEVC).also { hevc = it }

    private fun hasDecoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }.onFailure { e -> LinkLog.w(TAG) { "查询解码器失败: $e" } }.getOrDefault(false)

    private const val TAG = "CodecSupport"
}
