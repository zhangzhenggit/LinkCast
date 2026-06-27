package com.linkcast.receiver.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Advertises the accessory's AirPlay receiver over mDNS so the phone can discover the
 * video/audio endpoint (port 7000) and open the reverse stream after the control link.
 * Without this advert the phone connects the control channel but never reaches the
 * AirPlay server, so no video starts. Service shape mirrors the reference receiver.
 */
class AirPlayAdvertiser(
    context: Context,
    private val listener: (String) -> Unit,
) {
    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var regListener: NsdManager.RegistrationListener? = null

    fun start() {
        if (regListener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "Carplay"
            serviceType = "_airplay._tcp."
            port = AIRPLAY_PORT
            setAttribute("deviceid", "02:08:22:32:24:FF")
            setAttribute("features", "0x4040280,0x61")
            setAttribute("fv", "1.0")
            setAttribute("flags", "0x4")
            setAttribute("model", "Car IVI")
            setAttribute("protovers", "1.0")
            setAttribute("srcvers", "480.5")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                listener("AirPlay mDNS registered: ${serviceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                listener("AirPlay mDNS register failed $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = l
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener("registerService threw: $it"); regListener = null }
    }

    fun stop() {
        regListener?.let { runCatching { nsdManager.unregisterService(it) } }
        regListener = null
    }

    companion object {
        private const val AIRPLAY_PORT = 7000
    }
}
