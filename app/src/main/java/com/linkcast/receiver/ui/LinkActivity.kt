package com.linkcast.receiver.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.linkcast.receiver.CarPlayService
import com.linkcast.receiver.ConnectionStatus
import com.linkcast.receiver.LinkConfig
import com.linkcast.receiver.diag.LinkLog
import com.linkcast.receiver.input.InputForwarder

/**
 * 单 Activity、两态:
 * - 主页(Home):非全屏,左侧信息/控件、右上实时预览卡片(比例同全屏)、下方独立滚动日志;
 * - 投屏(Projection):沉浸全屏,SurfaceView 铺满 + 可拖动小白点返回。
 *
 * 视频流由 Service 拥有,本页只把当前应显示的 Surface 交给它(预览小窗 / 全屏,只改尺寸不重建)。
 * 未出图时隐藏 SurfaceView、显示状态占位,避免定格上一帧;只有"投屏中"才允许进全屏。
 */
class LinkActivity : Activity(), SurfaceHolder.Callback, CarPlayService.StatusListener {
    private enum class Mode { Home, Projection }

    private val inputForwarder = InputForwarder()
    private lateinit var config: LinkConfig

    private lateinit var surfaceView: SurfaceView
    private lateinit var previewStatus: TextView
    private lateinit var homeRoot: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var infoView: TextView
    private lateinit var autoButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var logSwitch: Button
    private lateinit var logCollapse: Button
    private lateinit var logScroll: ScrollView
    private lateinit var logBody: TextView
    private lateinit var dotController: AssistiveDotController

    private var mode = Mode.Home
    private var logCollapsed = false
    private val logLines = ArrayDeque<String>()

    private var screenW = 0
    private var screenH = 0
    private var previewW = 0
    private var previewH = 0

    private val uiLogSink = LinkLog.Sink { _, tag, message ->
        runOnUiThread {
            logLines.addLast("$tag: $message")
            while (logLines.size > LOG_MAX_LINES) logLines.removeFirst()
            logBody.text = logLines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = LinkConfig(this)
        requestRuntimePermissions()
        buildUi()
        dotController = AssistiveDotController(this) { setMode(Mode.Home) }
        setMode(Mode.Home)
        CarPlayService.start(this)
    }

    private fun buildUi() {
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        previewW = (screenW * 0.40f).toInt()
        previewH = (previewW * screenH.toFloat() / screenW).toInt()

        // 顶部预览触控:投屏中点击进全屏(未投屏时此 View 不可见,不触发)。
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@LinkActivity)
            setOnTouchListener { view, event ->
                if (mode == Mode.Projection) {
                    inputForwarder.onTouch(view, event)
                } else {
                    if (event.actionMasked == MotionEvent.ACTION_UP && isStreaming()) setMode(Mode.Projection)
                    true
                }
            }
        }
        previewStatus = TextView(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xff1c1f26.toInt())
            setTextColor(0xffb8c0cc.toInt())
            textSize = 15f
            text = "未连接"
        }

        // —— 左侧信息列 ——
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@LinkActivity).apply {
                text = "极连投屏 LinkCast"; setTextColor(0xffffffff.toInt()); textSize = 18f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@LinkActivity).apply {
                text = "⚙"
                setOnClickListener { startActivity(Intent(this@LinkActivity, SettingsActivity::class.java)) }
            })
        }
        statusView = TextView(this).apply { textSize = 16f; setTextColor(0xffffffff.toInt()) }
        detailView = TextView(this).apply { textSize = 12f; setTextColor(0xffb8c0cc.toInt()) }
        autoButton = Button(this).apply { setOnClickListener { toggleAuto() } }
        connectButton = Button(this).apply {
            text = "立即连接"; setOnClickListener { CarPlayService.connect(this@LinkActivity) }
        }
        disconnectButton = Button(this).apply {
            text = "断开"; setOnClickListener { CarPlayService.cancel(this@LinkActivity) }
        }
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(autoButton); addView(connectButton); addView(disconnectButton)
        }
        infoView = TextView(this).apply { textSize = 12f; setTextColor(0xffb8c0cc.toInt()); setPadding(0, dp(6), 0, 0) }

        val leftBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            addView(titleRow); addView(statusView); addView(detailView); addView(controlRow); addView(infoView)
        }
        // 顶部行:左信息(权重) + 右侧预览占位(实际预览/状态由顶层 overlay 显示)。
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(leftBlock, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(View(this@LinkActivity), LinearLayout.LayoutParams(previewW + dp(32), MATCH))
        }

        // —— 日志区(独立滚动)——
        logCollapse = Button(this).apply { setOnClickListener { toggleLogCollapse() } }
        logSwitch = Button(this).apply { setOnClickListener { toggleLogEnabled() } }
        val logClear = Button(this).apply { text = "清空"; setOnClickListener { logLines.clear(); logBody.text = "" } }
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(logCollapse, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(logSwitch); addView(logClear)
        }
        logBody = TextView(this).apply { textSize = 10f; setTextColor(0xff9aa6b2.toInt()); setPadding(dp(16), 0, dp(16), dp(12)) }
        logScroll = ScrollView(this).apply { addView(logBody) }

        homeRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff15171c.toInt())
            addView(topRow, LinearLayout.LayoutParams(MATCH, previewH + dp(24)))
            addView(logHeader)
            addView(logScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xff111318.toInt())
            addView(homeRoot, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(previewStatus, homePreviewParams())
            addView(surfaceView, homePreviewParams())
        })
        updateAutoButton(); updateLogControls()
    }

    private fun homePreviewParams() =
        FrameLayout.LayoutParams(previewW, previewH, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16); rightMargin = dp(16)
        }

    private fun isStreaming() = CarPlayService.connectionStatus.phase == ConnectionStatus.Phase.Connected

    // —— 模式切换 ——

    private fun setMode(next: Mode) {
        mode = next
        if (next == Mode.Home) {
            surfaceView.layoutParams = homePreviewParams()
            homeRoot.visibility = View.VISIBLE
            previewStatus.visibility = View.VISIBLE
            dotController.hide()
            showSystemBars()
        } else {
            surfaceView.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            homeRoot.visibility = View.GONE
            previewStatus.visibility = View.GONE
            enterFullscreen()
            dotController.show()
        }
        surfaceView.requestLayout()
    }

    // —— 控件 ——

    private fun toggleAuto() { CarPlayService.setAutoConnect(this, !config.autoConnectEnabled); updateAutoButton() }
    private fun updateAutoButton() { autoButton.text = if (config.autoConnectEnabled) "自动连接:开" else "自动连接:关" }

    private fun toggleLogCollapse() {
        logCollapsed = !logCollapsed
        logScroll.visibility = if (logCollapsed) View.GONE else View.VISIBLE
        updateLogControls()
    }

    private fun toggleLogEnabled() {
        val on = !config.diagLogEnabled
        config.diagLogEnabled = on
        LinkLog.enabled = on
        if (!on) { logLines.clear(); logBody.text = "日志已关闭" }
        updateLogControls()
    }

    private fun updateLogControls() {
        logCollapse.text = if (logCollapsed) "▸ 日志" else "▾ 日志"
        logSwitch.text = if (config.diagLogEnabled) "日志:开" else "日志:关"
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

    // —— Service 状态(主线程)——

    override fun onStatus(status: ConnectionStatus) {
        statusView.text = status.label
        statusView.setTextColor(colorFor(status.phase))
        previewStatus.text = previewWord(status.phase)
        val streaming = status.phase == ConnectionStatus.Phase.Connected
        // 出图才显示预览画面;否则隐藏 SurfaceView,露出状态占位(不留定格帧)。
        surfaceView.visibility = if (streaming) View.VISIBLE else View.INVISIBLE
        // 投屏全屏时若画面中断,自动退回主页(未投屏不停留在全屏)。
        if (mode == Mode.Projection && !streaming) setMode(Mode.Home)
        val working = status.phase == ConnectionStatus.Phase.Working || streaming
        connectButton.isEnabled = !working
        disconnectButton.isEnabled = working
        refreshInfo()
    }

    override fun onPhase(detail: String) { detailView.text = detail }

    override fun onLog(line: String) = Unit

    // 小窗提示词:简洁三态。
    private fun previewWord(phase: ConnectionStatus.Phase): String = when (phase) {
        ConnectionStatus.Phase.Off -> "未连接"
        ConnectionStatus.Phase.Working -> "连接中"
        ConnectionStatus.Phase.Paused -> "已断开"
        ConnectionStatus.Phase.Connected -> ""
    }

    private fun colorFor(phase: ConnectionStatus.Phase): Int = when (phase) {
        ConnectionStatus.Phase.Connected -> 0xff5cd65c.toInt()
        ConnectionStatus.Phase.Working -> 0xff5c9ad6.toInt()
        ConnectionStatus.Phase.Paused -> 0xffd6b15c.toInt()
        ConnectionStatus.Phase.Off -> 0xff9aa0a6.toInt()
    }

    // —— 生命周期 / Surface ——

    override fun onResume() {
        super.onResume()
        CarPlayService.statusListener = this
        LinkLog.addSink(uiLogSink)
        onStatus(CarPlayService.connectionStatus)
        CarPlayService.lastPhase.takeIf { it.isNotEmpty() }?.let { onPhase(it) }
        if (mode == Mode.Projection) dotController.show()
        updateAutoButton(); updateLogControls()
    }

    override fun onPause() {
        if (CarPlayService.statusListener === this) CarPlayService.statusListener = null
        LinkLog.removeSink(uiLogSink)
        dotController.hide()
        super.onPause()
    }

    override fun onDestroy() {
        if (CarPlayService.statusListener === this) CarPlayService.statusListener = null
        LinkLog.removeSink(uiLogSink)
        dotController.hide()
        CarPlayService.attachSurface(null, 0, 0)
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        CarPlayService.attachSurface(holder.surface, width, height)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { CarPlayService.attachSurface(null, 0, 0) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (mode == Mode.Projection && inputForwarder.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && mode == Mode.Projection) enterFullscreen()
    }

    private fun enterFullscreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
            if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.POST_NOTIFICATIONS); add(Manifest.permission.NEARBY_WIFI_DEVICES) }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 10)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val LOG_MAX_LINES = 200
    }
}
