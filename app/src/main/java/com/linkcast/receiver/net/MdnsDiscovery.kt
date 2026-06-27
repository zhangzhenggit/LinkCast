package com.linkcast.receiver.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.example.autoservice.carplay.CarplayNative
import java.net.Inet4Address
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * After the phone joins the projection AP it advertises its control endpoint over
 * mDNS as `_carplay-ctrl._tcp`. The accessory must discover and resolve it, then
 * hand the phone's address/port/TXT records to the native layer, which opens the
 * AirPlay video session back to the phone. Mirrors the reference NsdManager flow.
 */
class MdnsDiscovery(
    context: Context,
    private val interfaceName: String,
    private val listener: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicastLock =
        (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("linkcast-mdns").apply { setReferenceCounted(false) }
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "linkcast-mdns") }

    @Volatile private var discovering = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discovering) return
        discovering = true
        handedOff = false
        handedOffWithV4 = false
        attemptsWithoutV4 = 0
        runCatching { multicastLock.acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                listener("mDNS discovery start failed $errorCode")
                discovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {
                listener("mDNS discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) { discovering = false }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                listener("mDNS found ${serviceInfo.serviceName} / ${serviceInfo.serviceType}")
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                listener("mDNS lost ${serviceInfo.serviceName}")
            }
        }
        discoveryListener = l
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        }.onFailure { listener("discoverServices threw: $it"); discovering = false }
    }

    fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        discovering = false
        runCatching { if (multicastLock.isHeld) multicastLock.release() }
    }

    @Volatile private var handedOff = false

    private fun resolve(info: NsdServiceInfo) {
        // On API34+ the deprecated resolveService often returns an incomplete address
        // set (IPv6-only), which the native can't connect over. registerServiceInfoCallback
        // delivers the full, updated address list (incl. IPv4) — what we need.
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                nsdManager.registerServiceInfoCallback(
                    info, executor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            listener("mDNS info-callback reg failed $errorCode; falling back")
                            resolveLegacy(info)
                        }
                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                            handleResolved(serviceInfo)
                        }
                        override fun onServiceLost() {}
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                )
            }.onFailure {
                listener("registerServiceInfoCallback threw: $it; falling back")
                resolveLegacy(info)
            }
            return
        }
        resolveLegacy(info)
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(info: NsdServiceInfo) {
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                listener("mDNS resolve failed $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handleResolved(serviceInfo)
            }
        })
    }

    @Volatile private var handedOffWithV4 = false
    @Volatile private var attemptsWithoutV4 = 0

    private fun handleResolved(info: NsdServiceInfo) {
        val port = info.port
        val txt = packTxt(info)
        val deviceId = info.attributes["id"]?.let { String(it, StandardCharsets.US_ASCII) } ?: ""

        val raw = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.orEmpty().toList()
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }
        listener("mDNS raw addrs: " + raw.joinToString { it.hostAddress.orEmpty() })

        // The phone, once associated to our car AP, is reachable on the AP interface.
        // The ALWAYS-correct target is its link-local IPv6 carried WITH its %scope.
        // NsdManager often leaves Inet6Address.scopedInterface null but still encodes
        // the scope in hostAddress ("fe80::..%wlan2"); parse the iface name from there.
        val linkLocalStr = raw.filterIsInstance<java.net.Inet6Address>()
            .firstOrNull { it.isLinkLocalAddress }
            ?.hostAddress
        val scopeName = linkLocalStr?.substringAfter('%', "")?.takeIf { it.isNotBlank() }
        // AP interface: the link-local's scope, else a wlan AP iface with a private v4
        // that is NOT our STA uplink, else the configured default.
        val apIface = scopeName?.let { runCatching { java.net.NetworkInterface.getByName(it) }.getOrNull() }
            ?: detectApInterface()
        val apIfaceName = apIface?.name ?: scopeName ?: interfaceName
        val apV4Subnets = apIface?.interfaceAddresses
            ?.mapNotNull { ia -> (ia.address as? Inet4Address)?.let { it to ia.networkPrefixLength } }
            .orEmpty()

        val v4 = raw.filterIsInstance<Inet4Address>()
            .filter { a -> apV4Subnets.any { (local, prefix) -> sameSubnet(local, a, prefix.toInt()) } }
            .mapNotNull { it.hostAddress }
        // Link-local WITH scope (append the AP iface if NsdManager didn't): native needs
        // the scope — the earlier "interface 0 → EINVAL(22)" was a scopeless link-local.
        val v6 = linkLocalStr?.let {
            if (it.contains('%')) it else "$it%$apIfaceName"
        }?.let { listOf(it) }.orEmpty()

        // Hand off ONLY once we have the phone's AP-subnet IPv4. The native connects the
        // control channel over whatever we give it FIRST and latches onto it; if we hand
        // off a link-local first, it connects link-local and the phone then tries the
        // reverse AirPlay/video connection over link-local, which silently fails here.
        // So wait for the (DHCP-assigned, arrives within ~1-2s) IPv4 and give IPv4 only.
        if (v4.isEmpty()) {
            attemptsWithoutV4++
            // Fallback: if IPv4 never shows after several resolves, use the scoped
            // link-local rather than never connecting at all.
            if (attemptsWithoutV4 < 6 || handedOff) {
                listener("Phone resolved, waiting for AP IPv4 (try $attemptsWithoutV4); link-local only so far")
                return
            }
        }
        if (handedOff) return
        handedOff = true
        val hosts = (if (v4.isNotEmpty()) v4 else v6).distinct().joinToString(";")
        listener("Phone resolved $deviceId @ $hosts:$port (v4=${v4.isNotEmpty()}, if=$apIfaceName)")
        val rc = CarplayNative.onBrowseHandler(hosts, deviceId, apIfaceName, port, txt, 0)
        listener("onBrowseHandler($hosts, id=$deviceId, if=$apIfaceName, port=$port) -> $rc")
    }

    // The SoftAP interface: up, not loopback, name looks like a wlan/ap iface, has a
    // private IPv4, and is not the STA uplink (no default route via it — approximated by
    // preferring a non-"wlan0" wlan iface that has a /24-ish private address).
    private fun detectApInterface(): java.net.NetworkInterface? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { ni -> ni.name.startsWith("wlan") || ni.name.startsWith("ap") || ni.name.startsWith("swlan") }
            .filter { ni -> ni.interfaceAddresses.any { (it.address as? Inet4Address)?.isSiteLocalAddress == true } }
            // The STA uplink is usually wlan0; prefer any OTHER wlan iface (the SoftAP).
            .sortedBy { it.name == "wlan0" }
            .firstOrNull()
    }.getOrNull()

    private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
        val ab = a.address; val bb = b.address
        if (ab.size != 4 || bb.size != 4) return false
        val ai = ((ab[0].toInt() and 0xff) shl 24) or ((ab[1].toInt() and 0xff) shl 16) or
            ((ab[2].toInt() and 0xff) shl 8) or (ab[3].toInt() and 0xff)
        val bi = ((bb[0].toInt() and 0xff) shl 24) or ((bb[1].toInt() and 0xff) shl 16) or
            ((bb[2].toInt() and 0xff) shl 8) or (bb[3].toInt() and 0xff)
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return (ai and mask) == (bi and mask)
    }

    // TXT packed as the reference does: for each attr, [keyLen+valLen+1][key]'='[val].
    private fun packTxt(info: NsdServiceInfo): ByteArray {
        val out = ByteArray(1024)
        var i = 0
        for ((key, value) in info.attributes) {
            val keyBytes = key.toByteArray(StandardCharsets.US_ASCII)
            val valLen = value?.size ?: 0
            if (i + 1 + keyBytes.size + 1 + valLen > out.size) break
            out[i++] = (keyBytes.size + valLen + 1).toByte()
            System.arraycopy(keyBytes, 0, out, i, keyBytes.size)
            i += keyBytes.size
            out[i++] = '='.code.toByte()
            if (value != null && valLen > 0) {
                System.arraycopy(value, 0, out, i, valLen)
                i += valLen
            }
        }
        return out
    }

    companion object {
        private const val SERVICE_TYPE = "_carplay-ctrl._tcp"
    }
}
