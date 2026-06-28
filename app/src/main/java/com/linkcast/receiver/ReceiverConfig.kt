package com.linkcast.receiver

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.WindowManager

class ReceiverConfig(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(Prefs.CONFIG, Context.MODE_PRIVATE)

    val audioBuffers: Int get() = prefs.getInt("key_audio_buffers", 30)
    val codecType: Int get() = prefs.getInt("key_codec_type", 1)
    val videoCorner: Int get() = prefs.getInt("key_videostream_corner", 0)
    val density: Int get() = prefs.getInt("key_density", 0)
    val hotspotSsidFallback: String get() = prefs.getString("etx_wifi_name", "car") ?: "car"
    val hotspotPasswordFallback: String get() = prefs.getString("etx_wifi_pswd", "12345678") ?: "12345678"
    val useFiveGhz: Boolean get() = prefs.getInt("etx_wifi_band", 1) > 0
    // When true, do NOT start a LocalOnlyHotspot — rely on a user-managed system "car"
    // AP and just advertise its static creds. This matches how the original works on
    // this tablet: the AP is the SYSTEM tethering hotspot (its 2.4/5GHz band is chosen
    // by the user in Android hotspot settings), and the app only announces the channel.
    // LocalOnlyHotspot's band is system-random (often 2.4GHz) and unforceable, so manual
    // is the reliable, band-controllable path. Default ON.
    val manualHotspot: Boolean get() = prefs.getInt("manual_hotspot", 1) > 0
    val hotspotChannel: Int get() = prefs.getInt("etx_wifi_channel", 0)
    val autoConnect: Boolean get() = prefs.getInt("key_auto_connect", 1) > 0
    val defaultBluetoothAddress: String get() = prefs.getString("key_default_btdevice", "").orEmpty()
    val networkMfiEnabled: Boolean get() = prefs.getInt("networkmfi", 1) > 0

    // 投屏分辨率。已显式配置则用配置值,否则取屏幕实际尺寸(横向为宽)。
    fun selectedResolution(): Point {
        val parts = prefs.getString("key_set_resolutions", "").orEmpty()
            .split(",", "x", ";")
            .mapNotNull { it.trim().toIntOrNull() }
        if (parts.size >= 2 && parts[0] > 0 && parts[1] > 0) {
            return Point(parts[0] and -2, parts[1] and -2)
        }
        return displaySize()
    }

    // 屏幕尺寸(横向),并按 CarPlay 支持的上限等比缩小,避免分辨率过大无法显示。
    private fun displaySize(): Point {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        appContext.getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealMetrics(metrics)
        val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        if (longSide <= 0 || shortSide <= 0) return Point(1280, 720)
        return clampToMax(longSide, shortSide)
    }

    // 在不超过 MAX_WIDTH x MAX_HEIGHT 的前提下等比缩放(保持宽高比)。
    private fun clampToMax(width: Int, height: Int): Point {
        val scale = minOf(
            MAX_WIDTH.toFloat() / width,
            MAX_HEIGHT.toFloat() / height,
            1.0f,
        )
        val w = (width * scale).toInt() and -2
        val h = (height * scale).toInt() and -2
        return Point(w, h)
    }

    fun resolutionPayload(): ResolutionPayload {
        val target = selectedResolution()
        val factor = if (density < 0) density / 10.0f + 1.0f else density / 20.0f + 1.0f
        val scaledWidth = (target.x * factor).toInt() and -2
        val scaledHeight = (target.y * factor).toInt() and -2
        val resolutions = intArrayOf(scaledWidth, scaledHeight)
        val options = intArrayOf(
            target.x / 6,
            target.y / 6,
            (if (videoCorner == 0) 0 else 2) or codecType
        )
        return ResolutionPayload(resolutions, resolutions.size, options)
    }

    companion object {
        // CarPlay 投屏分辨率上限。
        private const val MAX_WIDTH = 1920
        private const val MAX_HEIGHT = 1080
    }
}

data class ResolutionPayload(
    val resolutions: IntArray,
    val count: Int,
    val options: IntArray,
)
