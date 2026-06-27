package com.linkcast.receiver.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.example.autoservice.carplay.CarplayNative
import com.linkcast.receiver.ReceiverConfig

/**
 * Brings up a Wi-Fi access point the phone can join for the projection video link.
 *
 * The phone is told the AP credentials over the (already authenticated) control
 * channel and then associates to that AP. The credentials MUST be those of an AP
 * that is actually running, so we start a LocalOnlyHotspot and read back the
 * system-assigned SSID/passphrase, then publish exactly those to the native layer.
 */
class HotspotProvider(
    private val context: Context,
    private val config: ReceiverConfig,
    private val listener: (String) -> Unit,
    private val deviceName: () -> String,
) {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val thread = HandlerThread("linkcast-hotspot").also { it.start() }
    private val handler = Handler(thread.looper)

    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var starting = false

    fun ensureStarted() {
        // Manual mode: the user runs a "car" AP themselves; never touch the radio (a
        // LocalOnlyHotspot attempt fails when tethering is active and can disrupt Wi-Fi).
        // Just advertise the static creds so the phone joins the user's AP.
        if (config.manualHotspot) {
            publishStatic()
            return
        }
        if (reservation != null || starting) {
            // Already up — re-publish current creds so a late WiFi request is answered.
            reservation?.let { publish(it) }
            return
        }
        starting = true
        listener("Starting local-only hotspot")
        runCatching {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    starting = false
                    publish(res)
                }

                override fun onFailed(reason: Int) {
                    starting = false
                    listener("Local-only hotspot failed reason=$reason")
                    // Fall back to the configured static creds so a manually
                    // pre-provisioned AP can still be advertised to the phone.
                    publishStatic()
                }

                override fun onStopped() {
                    reservation = null
                    starting = false
                    listener("Local-only hotspot stopped")
                }
            }, handler)
        }.onFailure {
            starting = false
            listener("startLocalOnlyHotspot threw: $it; using static creds")
            publishStatic()
        }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
        starting = false
    }

    private fun publish(res: WifiManager.LocalOnlyHotspotReservation) {
        val ssid: String
        val pass: String
        if (Build.VERSION.SDK_INT >= 30) {
            val cfg = res.softApConfiguration
            ssid = cfg.ssid?.removeSurrounding("\"").orEmpty()
            pass = cfg.passphrase.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val wc = res.wifiConfiguration
            ssid = wc?.SSID?.removeSurrounding("\"").orEmpty()
            @Suppress("DEPRECATION")
            pass = wc?.preSharedKey?.removeSurrounding("\"").orEmpty()
        }
        publishCredentials(ssid, pass)
    }

    private fun publishStatic() {
        publishCredentials(config.hotspotSsidFallback, config.hotspotPasswordFallback)
    }

    private fun publishCredentials(ssid: String, pass: String) {
        val channel = when {
            config.hotspotChannel > 0 -> config.hotspotChannel
            config.useFiveGhz -> 36
            else -> 1
        }
        Log.d("HotspotProvider", "Publishing hotspot ssid=$ssid channel=$channel")
        listener("Publishing hotspot credentials ssid=$ssid channel=$channel")
        // Mirrors the reference call: (ssid, passphrase, channel, 4, deviceName, "", "").
        CarplayNative.setWifiConfiguration(ssid, pass, channel, 4, deviceName(), "", "")
    }
}
