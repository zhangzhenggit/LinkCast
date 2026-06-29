package com.linkcast.receiver

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.WindowManager

class LinkConfig(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(Prefs.CONFIG, Context.MODE_PRIVATE)

    val audioBuffers: Int get() = prefs.getInt("key_audio_buffers", 30)
    val codecType: Int get() = prefs.getInt("key_codec_type", 1)
    val videoCorner: Int get() = prefs.getInt("key_videostream_corner", 0)
    val density: Int get() = prefs.getInt("key_density", 0)
    val hotspotSsidFallback: String get() = prefs.getString("etx_wifi_name", "car") ?: "car"
    val hotspotPasswordFallback: String get() = prefs.getString("etx_wifi_pswd", "12345678") ?: "12345678"
    // 手动模式下使用 5G 频段。默认 2.4G。
    val useFiveGhz: Boolean get() = prefs.getInt("etx_wifi_band", 0) > 0
    // 热点后端:
    //   0 = Wi-Fi Direct(P2P GO,默认):普通权限即可指定 5G 频段。
    //   2 = 手动(广告静态凭据,连接外部已开好的 AP),仅调试对照用。
    val hotspotMode: Int get() = prefs.getInt("hotspot_mode", HOTSPOT_WIFI_DIRECT)

    // 调试日志总开关。开启后项目自定义日志(经 LinkLog)才会输出。
    val diagLogEnabled: Boolean get() = prefs.getInt("diag_log", 1) > 0
    val hotspotChannel: Int get() = prefs.getInt("etx_wifi_channel", 0)
    val autoConnect: Boolean get() = prefs.getInt("key_auto_connect", 1) > 0
    val defaultBluetoothAddress: String get() = prefs.getString("key_default_btdevice", "").orEmpty()
    val networkMfiEnabled: Boolean get() = prefs.getInt("networkmfi", 1) > 0

    // 投屏分辨率。已显式配置则用配置值,否则取屏幕实际尺寸(横向为宽)。
    fun selectedResolution(): Point {
        // 测试开关:固定分辨率,用于验证卡顿是否与分辨率相关。两值 >0 时生效,设回 0 则走自适应。
        if (TEST_FORCE_WIDTH > 0 && TEST_FORCE_HEIGHT > 0) {
            return Point(TEST_FORCE_WIDTH and -2, TEST_FORCE_HEIGHT and -2)
        }
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

        // 测试用固定分辨率(设回 0 关闭,恢复自适应)。
        private const val TEST_FORCE_WIDTH = 0
        private const val TEST_FORCE_HEIGHT = 0

        // 热点后端取值。
        const val HOTSPOT_WIFI_DIRECT = 0
        const val HOTSPOT_MANUAL = 2
    }
}

data class ResolutionPayload(
    val resolutions: IntArray,
    val count: Int,
    val options: IntArray,
)
