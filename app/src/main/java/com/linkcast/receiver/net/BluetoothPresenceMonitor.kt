package com.linkcast.receiver.net

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.linkcast.receiver.diag.LinkLog

/**
 * 监听蓝牙子系统可用性,作为自动连接的"恢复/暂停"信号源:
 * - 适配器开(STATE_ON)/ 配对设备 ACL 连上 / 新配对完成(BOND_BONDED)→ 可用([onAvailable]),
 *   用于解除"蓝牙未连接"暂停;
 * - 适配器关(STATE_OFF)→ 不可用([onUnavailable])。
 *
 * 监听配对完成很关键:iPhone 常是"已配对 未连接"(idle-paired),配对动作既不触发 STATE_ON 也不触发
 * ACL_CONNECTED;没有这个信号,刚配好对时停在"蓝牙未连接"暂停态就无人唤醒。
 *
 * 注意:这里只关心"蓝牙整个能不能用",不负责发现/连接设备 —— 主动 RFCOMM 连接由 transport 的
 * 循环完成,手机走远/走近也由那条循环兜底,不在此处理。
 */
class BluetoothPresenceMonitor(
    private val context: Context,
    private val onAvailable: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> { LinkLog.i(TAG) { "蓝牙已开启" }; onAvailable() }
                        BluetoothAdapter.STATE_OFF -> { LinkLog.i(TAG) { "蓝牙已关闭" }; onUnavailable() }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device?.bondState == BluetoothDevice.BOND_BONDED) {
                        LinkLog.i(TAG) { "配对设备已连接: ${device.address}" }
                        onAvailable()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (bond == BluetoothDevice.BOND_BONDED) {
                        LinkLog.i(TAG) { "新配对完成" }
                        onAvailable()
                    }
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
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
