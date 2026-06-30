package com.linkcast.receiver

import android.os.Handler
import com.linkcast.receiver.diag.LinkLog

/**
 * 连接生命周期控制(自动连接状态机)。
 *
 * 顶层是一个总开关 [setAutoEnabled]:关闭时彻底 inert —— 不循环,任何环境事件都不能恢复;
 * 开启时进入"循环 + 暂停/恢复"逻辑。手动连接 [onManualConnect] 是独立的一次性动作,不受总开关约束。
 *
 * 形态:
 * - 正常 = 循环跑([ConnectionStatus.Searching]),底层 transport 自行重试 RFCOMM(走远走近靠它);
 * - RFCOMM 连上、认证/起会话 → [Connecting];出图 → [Streaming];
 * - 命中暂停条件 → [Paused](拆会话,零活动);命中恢复事件 → 重新尝试(会先做前置条件预检,
 *   条件仍不满足就自然退回对应 Paused,自愈)。
 *
 * 恢复规则:手动连接恢复一切;环境事件(蓝牙/网络)只恢复对应的环境型暂停;认证被拒/手动停止只认手动。
 *
 * 只决策"何时连/停",不碰底层会话:启停由宿主通过 [onStart]/[onStop] 注入;前置条件(权限/蓝牙/网络)
 * 由宿主以 lambda 提供,使本类与 Android 细节解耦。
 *
 * 连接阶段看门狗:进入 [Connecting] 起计时,出图([Streaming])撤销;在连接阶段卡死(如认证不前进)
 * 超时即重建会话,连续失败达上限则暂停,避免一直卡着空耗。等手机的 [Searching] 不设超时。
 * 调度依赖宿主传入的 [handler](服务 worker 线程),与会话操作同线程、无竞态。
 */
class ConnectionController(
    private val handler: Handler,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onStatusChanged: (ConnectionStatus) -> Unit,
    private val hasPermission: () -> Boolean,
    private val bluetoothReady: () -> Boolean,
    private val networkReady: () -> Boolean,
) {
    @Volatile var status: ConnectionStatus = ConnectionStatus.Off
        private set

    // 总开关:关闭时除手动外不启动、不恢复。
    private var autoEnabled = false
    // 底层会话是否已起(防重复启停)。
    private var sessionActive = false

    /** 总开关:自动连接开/关。关闭会停掉一切并屏蔽所有恢复事件。 */
    fun setAutoEnabled(on: Boolean) {
        if (autoEnabled == on) return
        autoEnabled = on
        LinkLog.i(TAG) { "自动连接总开关 = $on" }
        if (on) tryStart() else stopTo(ConnectionStatus.Off)
    }

    /** 用户手动"立即连接":清除任何暂停(含到期/手动停止),立即尝试一次。独立于总开关。 */
    fun onManualConnect() {
        LinkLog.i(TAG) { "手动连接" }
        tryStart(force = true)
    }

    /** 用户手动断开 —— 进暂停,只能手动恢复。 */
    fun onUserDisconnect() = stopTo(ConnectionStatus.Paused(PauseReason.UserStopped))

    /**
     * 整段重建当前会话:停掉再立即重起(如解码器回退后需以新码型重新协商、或会话卡死后自恢复)。
     * 仅在已有会话时有意义;保持原有意图(总开关不变)。
     */
    fun restartSession() {
        if (!sessionActive) return
        LinkLog.i(TAG) { "重建会话" }
        sessionActive = false
        onStop()
        tryStart(force = true)
    }

    /** 服务停止。 */
    fun onStopped() = stopTo(ConnectionStatus.Off)

    /** 认证被服务端拒绝(已成功签名却被拒,如到期/未授权)—— 终态,只能手动恢复。 */
    fun onAuthRejected() {
        LinkLog.w(TAG) { "认证被拒,暂停(不自动重试)" }
        stopTo(ConnectionStatus.Paused(PauseReason.AuthRejected))
    }

    /** 蓝牙恢复可用(适配器开/已连上)。 */
    fun onBluetoothAvailable() {
        if (autoEnabled && isPausedFor(PauseReason.BluetoothOff)) {
            LinkLog.i(TAG) { "蓝牙恢复,重新尝试" }
            tryStart()
        }
    }

    /** 蓝牙不可用(适配器关)。 */
    fun onBluetoothUnavailable() {
        if (autoEnabled && sessionActive) stopTo(ConnectionStatus.Paused(PauseReason.BluetoothOff))
    }

    /** 网络恢复。 */
    fun onNetworkAvailable() {
        if (autoEnabled && isPausedFor(PauseReason.NoNetwork)) {
            LinkLog.i(TAG) { "网络恢复,重新尝试" }
            tryStart()
        }
    }

    /**
     * RFCOMM 已连上(手机已接上,进入连接阶段)。据此进入 [Connecting] 并起看门狗 —— 即使之后 native
     * 因手机端未回握手而完全不上报状态,也能被连接阶段超时兜住,不会永久卡在"等待连接…"。
     */
    fun onLinkEngaged() {
        if (sessionActive) setStatus(ConnectionStatus.Connecting)
    }

    /** 喂入底层投屏状态(仅来自 native 上报的真实状态)。 */
    fun onProjectionState(state: ProjectionState) {
        if (!sessionActive) return
        when (state) {
            ProjectionState.VideoStream -> setStatus(ConnectionStatus.Streaming)
            ProjectionState.Authing, ProjectionState.AuthSucceeded,
            ProjectionState.Connecting, ProjectionState.Connected ->
                setStatus(ConnectionStatus.Connecting)
            // 一次尝试失败:transport 自身会继续重试 RFCOMM,这里只退回"等待连接",不拆会话。
            ProjectionState.Idle, ProjectionState.ConnectFailed, ProjectionState.AuthFailed ->
                setStatus(ConnectionStatus.Searching)
        }
    }

    // 尝试启动:先做前置条件预检,不满足则进对应 Paused;满足则起会话并进入 Searching。
    private fun tryStart(force: Boolean = false) {
        if (!force && !autoEnabled) return
        handler.removeCallbacks(retryRunnable)   // 取消待执行的间隔重试,本次立即尝试
        disarmConnectWatchdog()                  // 全新一次尝试:重置连接阶段计时
        val reason = preconditionPause()
        if (reason != null) {
            LinkLog.i(TAG) { "前置条件不满足,暂停: ${reason.label}" }
            stopTo(ConnectionStatus.Paused(reason))
            return
        }
        if (!sessionActive) {
            sessionActive = true
            onStart()
        }
        setStatus(ConnectionStatus.Searching)
    }

    // 前置条件(按优先级):缺权限 → 蓝牙不可用 → 没网。都满足返回 null。
    private fun preconditionPause(): PauseReason? = when {
        !hasPermission() -> PauseReason.NoPermission
        !bluetoothReady() -> PauseReason.BluetoothOff
        !networkReady() -> PauseReason.NoNetwork
        else -> null
    }

    private fun stopTo(next: ConnectionStatus) {
        handler.removeCallbacks(retryRunnable)
        disarmConnectWatchdog()
        if (sessionActive) {
            sessionActive = false
            onStop()
        }
        setStatus(next)
    }

    private fun isPausedFor(reason: PauseReason): Boolean {
        val s = status
        return s is ConnectionStatus.Paused && s.reason == reason
    }

    private fun setStatus(next: ConnectionStatus) {
        if (next == status) return
        status = next
        // 连接阶段看门狗:手机首次接上(首次进 Connecting)起算一次绝对计时;此后 Connecting↔Searching
        // 的抖动不重置、也不撤销,使整段尝试被 90s 钉死。出图(Streaming)即成功撤销。
        when (next) {
            ConnectionStatus.Connecting -> if (!watchdogArmed) armConnectWatchdog()
            ConnectionStatus.Streaming -> disarmConnectWatchdog()
            else -> Unit
        }
        onStatusChanged(next)
    }

    private var watchdogArmed = false
    private val connectWatchdog = Runnable { onConnectTimeout() }
    private val retryRunnable = Runnable { if (autoEnabled) tryStart() }

    private fun armConnectWatchdog() {
        watchdogArmed = true
        handler.removeCallbacks(connectWatchdog)
        handler.postDelayed(connectWatchdog, CONNECT_TIMEOUT_MS)
    }

    private fun disarmConnectWatchdog() {
        watchdogArmed = false
        handler.removeCallbacks(connectWatchdog)
    }

    // 连接阶段(从手机首次接上算起)超时仍未出图:本次失败 → 拆掉会话,等间隔后再起下一次(无限循环)。
    // 触发时状态可能是 Connecting 或抖动中的 Searching,以"看门狗在计时中"为准。
    // 间隔期保持"等待连接…";自动开关关闭时(纯手动尝试)不再重试,直接结束。
    private fun onConnectTimeout() {
        if (!watchdogArmed) return
        disarmConnectWatchdog()
        if (sessionActive) {
            sessionActive = false
            onStop()
        }
        if (autoEnabled) {
            LinkLog.w(TAG) { "连接阶段超时(${CONNECT_TIMEOUT_MS}ms),${RETRY_INTERVAL_MS / 1000}s 后重试" }
            setStatus(ConnectionStatus.Searching)
            handler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
        } else {
            LinkLog.w(TAG) { "连接阶段超时(${CONNECT_TIMEOUT_MS}ms),手动尝试结束" }
            setStatus(ConnectionStatus.Off)
        }
    }

    private companion object {
        const val TAG = "ConnectionController"
        // 单次连接阶段最长等待;从 RFCOMM 连上算起,只覆盖"加入热点→认证→出图"(签名/排队在此之前,
        // 不计入),正常仅数秒,故 30s 足够;超过即判失败。
        const val CONNECT_TIMEOUT_MS = 30_000L
        // 一次连接失败后,等待多久再发起下一次。
        const val RETRY_INTERVAL_MS = 30_000L
    }
}
