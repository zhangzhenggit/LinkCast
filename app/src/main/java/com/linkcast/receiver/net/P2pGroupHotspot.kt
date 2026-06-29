package com.linkcast.receiver.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.linkcast.receiver.diag.LinkLog

/**
 * 用 Wi-Fi Direct 建一个 Group Owner 作为投屏热点。
 *
 * 普通应用权限即可通过 GROUP_OWNER_BAND_5GHZ 指定 5G 频段,并读出系统生成的 SSID/密码
 * 交给上层广告给 iPhone。建组是异步的,组就绪后通过 onReady 回调把凭据交出去。
 *
 * 流畅度取决于本机单射频要同时驻留几个信道:GO 与 STA 同信道、或无 STA(GO 独占)时为
 * 单信道,流畅;两者不同信道(含跨频段)则时分轮流(MCC),画面卡顿。
 */
class P2pGroupHotspot(
    private val context: Context,
    private val warn: (String) -> Unit,
) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    // channel 只初始化一次并全程复用:反复 initialize 会让框架状态错乱,导致 createGroup 报 ERROR。
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    @Volatile private var onReady: ((String, String) -> Unit)? = null
    @Volatile private var started = false

    // 组连接状态变化时拉取一次组信息(此时 SSID/密码才可读)。
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                requestGroupInfo()
            }
        }
    }

    /** 组就绪后回调 (ssid, passphrase)。可能在建组完成后稍有延迟。 */
    @SuppressLint("MissingPermission")
    fun start(onReady: (String, String) -> Unit) {
        val mgr = manager ?: run { LinkLog.w(TAG) { "本机不支持 Wi-Fi P2P" }; return }
        this.onReady = onReady
        val ch = ensureChannel(mgr) ?: run { LinkLog.w(TAG) { "Wi-Fi P2P channel 不可用" }; return }
        if (started) {
            // 已在运行:再拉一次组信息,补答一次凭据。
            requestGroupInfo()
            return
        }
        started = true
        logEnvDiagnostics()
        warnIfStaConflict()
        // 若系统里已存在我们自己的组(上次未正常 stop 残留)则直接复用,避免 remove 异步未完成时
        // 紧接着 createGroup 撞上残留组报 BUSY、逐级失败卡死。无我们的组时再清残留并新建。
        mgr.requestGroupInfo(ch) { group ->
            if (group != null && group.networkName == GROUP_SSID && !group.passphrase.isNullOrEmpty()) {
                LinkLog.d(TAG) { "复用已存在的 Wi-Fi Direct 组" }
                onReady?.invoke(group.networkName, group.passphrase)
            } else {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = createGroup(mgr, ch)
                    override fun onFailure(reason: Int) = createGroup(mgr, ch)
                })
            }
        }
    }

    // 逐级尝试建组,失败自动降级到下一档,确保最终能起一个组:
    //   tier 0: STA 在非 DFS 5G 信道时与之同频(co-channel,单信道,最流畅);否则请求 5G 频段
    //   tier 1: 请求 5G 频段(由固件挑非 DFS 5G 信道)
    //   tier 2: 无参建组(固件自选,可能落 2.4G)
    @SuppressLint("MissingPermission")
    private fun createGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, tier: Int = 0) {
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                LinkLog.d(TAG) { "Wi-Fi Direct 建组成功 tier=$tier" }
                requestGroupInfo()
            }
            override fun onFailure(reason: Int) {
                LinkLog.w(TAG) { "Wi-Fi Direct 建组失败 tier=$tier reason=$reason" }
                if (tier < 2) createGroup(mgr, ch, tier + 1)
            }
        }
        val config = buildConfig(tier)
        if (config != null) {
            mgr.createGroup(ch, config, listener)
        } else {
            mgr.createGroup(ch, listener)
        }
    }

    // 部分 ROM 的 build() 要求 networkName 与 passphrase 必须成对设置(否则需 peer 地址),都显式给出。
    private fun buildConfig(tier: Int): WifiP2pConfig? {
        if (Build.VERSION.SDK_INT < 29 || tier >= 2) return null
        val staFreq = currentStaFrequencyMhz()
        return runCatching {
            val builder = WifiP2pConfig.Builder()
                .setNetworkName(GROUP_SSID)
                .setPassphrase(GROUP_PASSPHRASE)
            if (tier == 0 && isNonDfs5g(staFreq)) {
                LinkLog.d(TAG) { "Wi-Fi Direct GO 与 STA 同频 ${staFreq}MHz(co-channel)" }
                builder.setGroupOperatingFrequency(staFreq)
            } else {
                LinkLog.d(TAG) { "Wi-Fi Direct GO 请求 5G 频段(STA=$staFreq tier=$tier)" }
                builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            }
            builder.build()
        }.onFailure { e -> LinkLog.w(TAG) { "Wi-Fi Direct 配置构建失败: $e" } }.getOrNull()
    }

    // 非 DFS 的 5G 信道:UNII-1(5180-5240)与 UNII-3(5745-5825),GO 可直接驻留无需雷达检测。
    private fun isNonDfs5g(freqMhz: Int): Boolean =
        freqMhz in 5180..5240 || freqMhz in 5745..5825

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.requestGroupInfo(ch) { group ->
            val ssid = group?.networkName.orEmpty()
            val pass = group?.passphrase.orEmpty()
            // 只发布我们自己的组,避免误把系统中他方的 P2P 组当成投屏热点。
            if (ssid == GROUP_SSID && pass.isNotEmpty()) {
                LinkLog.d(TAG) { "Wi-Fi Direct 就绪 ssid=$ssid" }
                onReady?.invoke(ssid, pass)
            }
        }
    }

    // 启动时打一行环境诊断,便于在不同设备(如 Android 9 车机)上快速判断会落在哪种形态。
    @SuppressLint("MissingPermission")
    private fun logEnvDiagnostics() {
        val support5g = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)?.is5GHzBandSupported
        }.getOrNull() == true
        val staFreq = currentStaFrequencyMhz()
        val predict = when {
            staFreq == 0 -> "GO 独占射频,流畅"
            isNonDfs5g(staFreq) -> "GO 可与 STA 同频,流畅"
            else -> "GO 与 STA 不同信道,可能卡顿"
        }
        LinkLog.i(TAG) {
            "环境诊断 API=${Build.VERSION.SDK_INT} 支持5G=$support5g STA=${staFreq}MHz 预测=$predict"
        }
    }

    // 当有 WiFi 联网且 GO 无法与其同信道(STA 在 2.4G 或 5G DFS)时,必然 MCC,弹非强制提示。
    private fun warnIfStaConflict() {
        val staFreq = currentStaFrequencyMhz()
        if (staFreq > 0 && !isNonDfs5g(staFreq)) {
            warn("检测到 WiFi 联网占用,投屏画面可能不流畅")
        }
    }

    // 当前 STA(联网)的频率(MHz);未连接或读取失败返回 0。
    @SuppressLint("MissingPermission")
    private fun currentStaFrequencyMhz(): Int {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return 0
        @Suppress("DEPRECATION")
        val freq = wm.connectionInfo?.frequency ?: 0
        return if (freq > 0) freq else 0
    }

    // 初始化并复用同一个 channel(只在首次或断开后重建);顺带注册一次组状态广播。
    private fun ensureChannel(mgr: WifiP2pManager): WifiP2pManager.Channel? {
        channel?.let { return it }
        val ch = mgr.initialize(context, Looper.getMainLooper()) {
            // 框架断开 channel(如 WiFi 重置):清空,下次 ensureChannel 重建。
            LinkLog.w(TAG) { "Wi-Fi P2P channel 断开" }
            channel = null
        }
        channel = ch
        if (ch != null && !receiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        return ch
    }

    // 停止当前组(下次 start 会重建),但保留 channel 与广播注册以便复用。
    fun stop() {
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            runCatching { mgr.removeGroup(ch, null) }
        }
        started = false
        onReady = null
    }

    // 彻底释放(服务销毁时调用):移除组、注销广播。
    fun release() {
        stop()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    companion object {
        private const val TAG = "P2pGroupHotspot"
        // Wi-Fi Direct 组名必须以 "DIRECT-" 开头;密码 8-63 位。
        private const val GROUP_SSID = "DIRECT-LinkCast"
        private const val GROUP_PASSPHRASE = "12345678"
    }
}
