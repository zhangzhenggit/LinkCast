package com.linkcast.receiver

import android.os.Handler
import com.linkcast.receiver.diag.LinkLog

/**
 * 连接生命周期控制(事件驱动)。
 *
 * 由事件决定何时连接/重试/停止:
 * - 蓝牙(iPhone)连上 或 用户手动连接 → 发起连接;
 * - 意外失败 → 按上限([MAX_RECONNECTS])间隔重试;
 * - 重试用尽 → 停止主动重连,改由"网络恢复"等事件再次触发(应对上车没网、后来有网);
 * - 蓝牙断开(手机离开)/ 用户取消 / 认证被拒(到期)→ 立即停止,不重试。
 *
 * 只决策"何时",不碰底层会话:启动/停止动作由宿主通过 [onStart]/[onStop] 注入;
 * 调度依赖宿主传入的 [handler](服务 worker 线程),与会话操作同线程、无竞态。
 */
class ConnectionController(
    private val handler: Handler,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onStatusChanged: (Status) -> Unit,
) {
    enum class Status { Idle, Connecting, Connected, Reconnecting, Stopped }

    @Volatile var status: Status = Status.Idle
        private set

    // 是否希望保持连接(蓝牙在场/用户发起为真;蓝牙断开/取消/到期/服务停止为假)。
    private var wantConnected = false
    private var reconnectCount = 0
    private var retryScheduled = false

    /** 用户手动发起连接。 */
    fun onUserConnect() = beginConnect()

    /** 蓝牙(iPhone)已连接 —— 触发连接。 */
    fun onBluetoothConnected() = beginConnect()

    /**
     * 蓝牙断开 —— 仅当处于已连接态时才判定为"手机离开"并停止。
     * 重连/拆会话过程中我们自己关闭 RFCOMM 也会触发 ACL 断开,这些状态下忽略,避免误杀重连。
     */
    fun onBluetoothDisconnected() {
        if (wantConnected && status == Status.Connected) {
            LinkLog.i(TAG) { "蓝牙断开(手机离开),停止连接" }
            stop(Status.Idle)
        }
    }

    /** 用户主动取消。 */
    fun onUserCancel() = stop(Status.Idle)

    /** 服务停止。 */
    fun onStopped() = stop(Status.Stopped)

    /** 认证被服务端拒绝(已成功签名却被拒,如到期/未授权)—— 终态,不重试。 */
    fun onAuthRejected() {
        LinkLog.w(TAG) { "认证被拒绝,停止连接(不重试)" }
        stop(Status.Stopped)
    }

    /** 网络恢复 —— 若仍想连且此前已放弃,则重置计数并重连。 */
    fun onNetworkAvailable() {
        if (wantConnected && status == Status.Stopped) {
            LinkLog.i(TAG) { "网络恢复,重新连接" }
            reconnectCount = 0
            attempt()
        }
    }

    /** 喂入底层投屏状态(仅来自 native 上报的真实状态)。 */
    fun onProjectionState(state: ProjectionState) {
        if (!wantConnected) return
        when (state) {
            ProjectionState.Connected, ProjectionState.VideoStream -> {
                reconnectCount = 0
                cancelScheduledRetry()
                setStatus(Status.Connected)
            }
            ProjectionState.Authing, ProjectionState.AuthSucceeded, ProjectionState.Connecting ->
                setStatus(Status.Connecting)
            ProjectionState.Idle, ProjectionState.ConnectFailed, ProjectionState.AuthFailed ->
                onAttemptFailed()
        }
    }

    private fun beginConnect() {
        // 已在连接中/已连上则忽略重复触发(用户与蓝牙事件可能同时到达)。
        if (wantConnected && (status == Status.Connecting || status == Status.Connected)) return
        wantConnected = true
        reconnectCount = 0
        cancelScheduledRetry()
        setStatus(Status.Connecting)
        onStart()
    }

    // 一次连接尝试失败:先拆掉失败的会话(避免底层在等待间隔内反复重试),再按上限重试或放弃。
    private fun onAttemptFailed() {
        // 已在等待重试或已放弃,忽略重复失败上报(含拆会话时 native 回报的 Idle)。
        if (status == Status.Reconnecting || status == Status.Stopped) return
        if (reconnectCount >= MAX_RECONNECTS) {
            LinkLog.i(TAG) { "重连已达上限($MAX_RECONNECTS 次),停止,等待事件再触发" }
            setStatus(Status.Stopped)
            onStop()
            return
        }
        setStatus(Status.Reconnecting)
        onStop()
        retryScheduled = true
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private val retryRunnable = Runnable {
        retryScheduled = false
        if (wantConnected && status == Status.Reconnecting) {
            reconnectCount++
            LinkLog.i(TAG) { "自动重连 第 $reconnectCount/$MAX_RECONNECTS 次" }
            attempt()
        }
    }

    private fun attempt() {
        setStatus(Status.Connecting)
        onStart()
    }

    private fun stop(finalStatus: Status) {
        wantConnected = false
        cancelScheduledRetry()
        setStatus(finalStatus)
        onStop()
    }

    private fun cancelScheduledRetry() {
        if (retryScheduled) {
            retryScheduled = false
            handler.removeCallbacks(retryRunnable)
        }
    }

    private fun setStatus(next: Status) {
        if (next != status) {
            status = next
            onStatusChanged(next)
        }
    }

    companion object {
        private const val TAG = "ConnectionController"
        private const val MAX_RECONNECTS = 3
        private const val RETRY_DELAY_MS = 10_000L
    }
}
