package com.linkcast.receiver

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point

class ReceiverConfig(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("SP", Context.MODE_PRIVATE)

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
    val networkMfiHost: String get() = prefs.getString("host_ip", "183.36.35.43").orEmpty()
    val networkMfiPorts: IntArray
        get() = prefs.getString("host_ports", "").orEmpty()
            .split(",", ";", "|")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }
            .toIntArray()

    var networkMfiPortIndex: Int
        get() = prefs.getInt("key_server_port", -1)
        set(value) {
            prefs.edit().putInt("key_server_port", value).apply()
        }

    fun selectedResolution(): Point {
        val raw = prefs.getString("key_set_resolutions", "1280,720") ?: "1280,720"
        val parts = raw.split(",", "x", ";").mapNotNull { it.trim().toIntOrNull() }
        val width = parts.getOrNull(0)?.coerceIn(640, 1920) ?: 1280
        val height = parts.getOrNull(1)?.coerceIn(360, 1080) ?: 720
        return Point(width and -2, height and -2)
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
}

data class ResolutionPayload(
    val resolutions: IntArray,
    val count: Int,
    val options: IntArray,
)
