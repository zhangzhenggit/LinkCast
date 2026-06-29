package com.linkcast.receiver.net

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.linkcast.receiver.diag.LinkLog

/**
 * 监听 iPhone 蓝牙(ACL)连接/断开,作为发起/停止连接的触发器:
 * 蓝牙连上即触发连接,手机离开蓝牙断开则停止。只关注已配对设备。
 */
class BluetoothPresenceMonitor(
    private val context: Context,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (device.bondState != BluetoothDevice.BOND_BONDED) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    LinkLog.i(TAG) { "蓝牙已连接: ${device.address}" }
                    onConnected()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    LinkLog.i(TAG) { "蓝牙已断开: ${device.address}" }
                    onDisconnected()
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // 系统受保护广播,用 EXPORTED 注册以确保收到。
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private companion object {
        const val TAG = "BluetoothPresenceMonitor"
    }
}
