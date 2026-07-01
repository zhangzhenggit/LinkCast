package com.linkcast.receiver.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.linkcast.receiver.CarPlayService
import com.linkcast.receiver.ConnectionStatus
import com.linkcast.receiver.LinkConfig

/** 主控制/状态区:标题+设置入口、主状态(配色)+细节、自动连接开关、连接/断开、诊断信息。 */
class ControlFragment : Fragment() {
    private lateinit var config: LinkConfig
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var infoView: TextView
    private lateinit var autoButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var logButton: Button

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        config = LinkConfig(ctx)

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                text = "极连投屏 LinkCast"; setTextColor(0xffffffff.toInt()); textSize = 18f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(ctx).apply {
                text = "⚙"
                setOnClickListener { startActivity(Intent(ctx, SettingsActivity::class.java)) }
            })
        }
        statusView = TextView(ctx).apply { textSize = 16f; setTextColor(0xffffffff.toInt()) }
        detailView = TextView(ctx).apply { textSize = 12f; setTextColor(0xffb8c0cc.toInt()) }
        autoButton = Button(ctx).apply { setOnClickListener { toggleAuto() } }
        connectButton = Button(ctx).apply { text = "立即连接"; setOnClickListener { CarPlayService.connect(ctx) } }
        disconnectButton = Button(ctx).apply { text = "断开"; setOnClickListener { CarPlayService.cancel(ctx) } }
        logButton = Button(ctx).apply {
            setOnClickListener { (activity as? LinkHost)?.toggleLog(); refreshLogButton() }
        }
        val controlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(autoButton); addView(connectButton); addView(disconnectButton); addView(logButton)
        }
        infoView = TextView(ctx).apply { textSize = 12f; setTextColor(0xffb8c0cc.toInt()) }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(titleRow); addView(statusView); addView(detailView); addView(controlRow); addView(infoView)
        }
        return ScrollView(ctx).apply { addView(content) }
    }

    override fun onResume() {
        super.onResume()
        render(CarPlayService.connectionStatus)
        CarPlayService.lastPhase.takeIf { it.isNotEmpty() }?.let { renderDetail(it) }
        refreshLogButton()
    }

    /** 刷新"日志浮窗"按钮文案(反映浮窗开/关)。 */
    fun refreshLogButton() {
        if (view == null) return
        logButton.text = if ((activity as? LinkHost)?.isLogShown == true) "日志浮窗:开" else "日志浮窗:关"
    }

    fun render(status: ConnectionStatus) {
        if (view == null) return
        statusView.text = status.label
        statusView.setTextColor(colorFor(status.phase))
        val working = status.phase == ConnectionStatus.Phase.Working || status.phase == ConnectionStatus.Phase.Connected
        connectButton.isEnabled = !working
        disconnectButton.isEnabled = working
        updateAuto()
        refreshInfo()
    }

    fun renderDetail(detail: String) { if (view != null) detailView.text = detail }

    private fun toggleAuto() {
        CarPlayService.setAutoConnect(requireContext(), !config.autoConnectEnabled)
        updateAuto()
    }

    private fun updateAuto() {
        autoButton.text = if (config.autoConnectEnabled) "自动连接:开" else "自动连接:关"
    }

    private fun refreshInfo() {
        val res = config.selectedResolution()
        infoView.text = buildString {
            appendLine("自动连接: ${if (config.autoConnectEnabled) "开" else "关"}")
            appendLine("码型: ${if (config.effectiveCodecType() == LinkConfig.CODEC_HEVC) "HEVC" else "H.264"}")
            appendLine("分辨率: ${res.x}x${res.y}")
            appendLine("热点: ${if (config.hotspotMode == LinkConfig.HOTSPOT_MANUAL) "手动 AP" else "Wi-Fi Direct"}")
            append("蓝牙: ${config.defaultBluetoothAddress.ifBlank { "自动选择已配对设备" }}")
        }
    }

    private fun colorFor(phase: ConnectionStatus.Phase): Int = when (phase) {
        ConnectionStatus.Phase.Connected -> 0xff5cd65c.toInt()
        ConnectionStatus.Phase.Working -> 0xff5c9ad6.toInt()
        ConnectionStatus.Phase.Paused -> 0xffd6b15c.toInt()
        ConnectionStatus.Phase.Off -> 0xff9aa0a6.toInt()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
