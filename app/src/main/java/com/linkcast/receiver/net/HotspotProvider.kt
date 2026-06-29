package com.linkcast.receiver.net

import android.content.Context
import com.example.autoservice.carplay.CarplayNative
import com.linkcast.receiver.LinkConfig
import com.linkcast.receiver.diag.LinkLog

/**
 * 准备投屏所需的 Wi-Fi 接入点,并把其 SSID/密码/信道交给原生层;iPhone 通过(已认证的)
 * 控制通道拿到这组凭据后据此关联。
 *
 * 默认使用 Wi-Fi Direct 自建组([P2pGroupHotspot]):普通应用权限即可指定 5G 频段。
 * 手动后端([ManualAp])仅用于调试对照,默认不启用。
 */
class HotspotProvider(
    private val context: Context,
    private val config: LinkConfig,
    private val warn: (String) -> Unit,
    private val deviceName: () -> String,
) {
    private val p2p = P2pGroupHotspot(context, warn)
    @Volatile private var p2pCreds: Pair<String, String>? = null

    private val manualAp = ManualAp(config.hotspotSsidFallback, config.hotspotPasswordFallback)

    fun ensureStarted() {
        when (config.hotspotMode) {
            LinkConfig.HOTSPOT_MANUAL -> publishManual()
            else -> ensureWifiDirect()
        }
    }

    // Wi-Fi Direct 建组是异步的:组就绪前先把上次缓存的凭据顶上,就绪回调里再用真实凭据覆盖。
    private fun ensureWifiDirect() {
        p2pCreds?.let { publishCredentials(it.first, it.second) }
        p2p.start { ssid, pass ->
            p2pCreds = ssid to pass
            publishCredentials(ssid, pass)
        }
    }

    private fun publishManual() {
        val (ssid, pass) = manualAp.credentials()
        publishCredentials(ssid, pass)
    }

    // 原生引擎启动前先写入一组占位凭据(真实凭据在热点就绪后覆盖),让其顺利完成初始化。
    fun publishPlaceholder() {
        publishCredentials(config.hotspotSsidFallback, config.hotspotPasswordFallback)
    }

    fun stop() {
        runCatching { p2p.stop() }
        p2pCreds = null
    }

    // 彻底释放(服务销毁时):移除组并注销 P2P 广播。
    fun release() {
        runCatching { p2p.release() }
        p2pCreds = null
    }

    private fun publishCredentials(ssid: String, pass: String) {
        val channel = chooseChannel()
        LinkLog.d(TAG) { "上报热点凭据 ssid=$ssid channel=$channel" }
        // 对应原始调用:(ssid, passphrase, channel, 4, deviceName, "", "")。
        CarplayNative.setWifiConfiguration(ssid, pass, channel, 4, deviceName(), "", "")
    }

    // 选择上报给 iPhone 的信道。iPhone 拿到凭据后只按 SSID 在对应频段内扫描关联,
    // 信道不必与热点实际信道精确一致,只要频段(2.4G/5G)对得上即可关联(已实测验证)。
    private fun chooseChannel(): Int {
        if (config.hotspotChannel > 0) return config.hotspotChannel
        return when (config.hotspotMode) {
            // 手动后端:广告频段需与外部 AP 的实际频段一致。
            LinkConfig.HOTSPOT_MANUAL ->
                if (config.useFiveGhz) DEFAULT_5G_CHANNEL else DEFAULT_2G_CHANNEL
            // Wi-Fi Direct 建组优先请求 5G,故上报 5G 信道。
            else -> DEFAULT_5G_CHANNEL
        }
    }

    companion object {
        private const val TAG = "HotspotProvider"
        // 缺省 2.4G 信道(覆盖广、各区域通用)。
        private const val DEFAULT_2G_CHANNEL = 6
        // 缺省 5G 信道。
        private const val DEFAULT_5G_CHANNEL = 36
    }
}
