package com.linkcast.receiver.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.linkcast.receiver.diag.LinkLog

/**
 * 监听网络可用事件。用于"上车时没网、随后有网"场景下重新触发连接(签名需要网络)。
 */
class NetworkMonitor(
    context: Context,
    private val onAvailable: () -> Unit,
) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            LinkLog.d(TAG) { "网络可用" }
            onAvailable()
        }
    }

    fun start() {
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
            .onFailure { e -> LinkLog.w(TAG) { "注册网络回调失败: $e" } }
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
