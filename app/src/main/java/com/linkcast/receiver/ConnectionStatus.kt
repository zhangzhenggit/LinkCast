package com.linkcast.receiver

/**
 * 对外暴露的连接状态(供 UI 显示)。把循环连接的整个生命周期收敛到有限几个状态,
 * 每个状态自带显示文案与大类 [Phase](UI 据此选颜色/图标)。
 *
 * 仅用于展示与判断,真正的连接动作由 [ConnectionController] 驱动。
 */
sealed class ConnectionStatus(val label: String, val phase: Phase) {
    enum class Phase { Off, Working, Connected, Paused }

    /** 自动连接关闭、且无手动会话:完全空闲。 */
    object Off : ConnectionStatus("自动连接已关闭", Phase.Off)

    /** 循环运行中,RFCOMM 尚未连上(含手机走远时的持续重试)。 */
    object Searching : ConnectionStatus("等待连接…", Phase.Working)

    /** RFCOMM 已连上,正在认证 / 起热点 / 起引擎。 */
    object Connecting : ConnectionStatus("连接中…", Phase.Working)

    /** 已出图投屏。 */
    object Streaming : ConnectionStatus("投屏中", Phase.Connected)

    /** 因某原因暂停(零活动,等恢复事件)。 */
    data class Paused(val reason: PauseReason) : ConnectionStatus(reason.label, Phase.Paused)
}

/**
 * 暂停原因。恢复规则分两类:
 * - 环境型(蓝牙/网络):对应环境事件恢复即可,也可手动恢复;
 * - 意图/终态型(认证被拒/手动停止):只能由手动连接恢复,环境事件不触发。
 * 总开关关闭时,任何事件都不恢复。
 */
enum class PauseReason(val label: String) {
    BluetoothOff("蓝牙未连接"),
    NoNetwork("无网络连接"),
    AuthRejected("认证被拒 / 试用到期"),
    UserStopped("已暂停（手动断开）"),
    NoPermission("缺少蓝牙权限"),
}
