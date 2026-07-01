package com.linkcast.receiver.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.linkcast.receiver.CarPlayService
import com.linkcast.receiver.ConnectionStatus
import com.linkcast.receiver.R
import com.linkcast.receiver.input.InputForwarder

/**
 * 宿主 Activity:承载三个 Fragment(控制/预览/日志,按屏形态组合)、管理"主页/投屏"两态与视频流归属。
 *
 * 视频流由 Service 拥有;预览(PreviewFragment 内)与全屏(本 Activity 顶层)各一个 SurfaceView,
 * 按当前模式把活动的那个 Surface 交给 Service(setOutputSurface 热切 + 占位保活)。投屏态用悬浮
 * 小白点([AssistiveDotController])返回。
 */
class LinkActivity : FragmentActivity(), CarPlayService.StatusListener, LinkHost, SurfaceHolder.Callback {
    private enum class Mode { Home, Projection }

    private val inputForwarder = InputForwarder()
    private lateinit var homeView: View
    private lateinit var fullscreenSurface: SurfaceView
    private lateinit var dotController: AssistiveDotController

    private var mode = Mode.Home
    private var screenW = 0
    private var screenH = 0

    private var previewSurface: Surface? = null
    private var previewW = 0
    private var previewH = 0
    private var fsSurface: Surface? = null
    private var fsW = 0
    private var fsH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        homeView = buildHome()
        fullscreenSurface = SurfaceView(this).apply {
            holder.addCallback(this@LinkActivity)
            visibility = View.GONE
            // 全屏投屏:把触控转发给 CarPlay(按当前 SurfaceView 尺寸换算到投屏分辨率)。
            setOnTouchListener { v, e -> inputForwarder.onTouch(v, e) }
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xff111318.toInt())
            addView(homeView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(fullscreenSurface, FrameLayout.LayoutParams(MATCH, MATCH))
        })

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.control_container, ControlFragment())
                .add(R.id.preview_container, PreviewFragment())
                .add(R.id.log_container, LogFragment())
                .commitNow()
        }

        dotController = AssistiveDotController(this) { setMode(Mode.Home) }
        requestRuntimePermissions()
        setMode(Mode.Home)
        CarPlayService.start(this)
    }

    // 按屏幕宽高比组合三个 Fragment 容器:细长屏左右排、接近方形上下排。
    private fun buildHome(): View {
        val wide = screenW.toFloat() / screenH >= 1.5f
        val control = container(R.id.control_container)
        val preview = container(R.id.preview_container)
        val log = container(R.id.log_container)
        // 预览保持全屏比例的缩略;细长屏取屏宽 40%,方形屏取 58%。
        val pw = (screenW * (if (wide) 0.40f else 0.58f)).toInt()
        val ph = (pw * screenH.toFloat() / screenW).toInt()
        return if (wide) {
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(control, LinearLayout.LayoutParams(0, MATCH, 1f))
                addView(preview, LinearLayout.LayoutParams(pw, MATCH))
            }
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(top, LinearLayout.LayoutParams(MATCH, ph))
                addView(log, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
        } else {
            val previewParams = LinearLayout.LayoutParams(pw, ph).apply { gravity = Gravity.CENTER_HORIZONTAL }
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(preview, previewParams)
                addView(control, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(log, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
        }
    }

    private fun container(id: Int) = FrameLayout(this).apply { this.id = id }

    // —— LinkHost ——

    override val isStreaming: Boolean
        get() = CarPlayService.connectionStatus.phase == ConnectionStatus.Phase.Connected

    override fun requestProjection() { if (isStreaming) setMode(Mode.Projection) }

    override fun onPreviewSurface(surface: Surface?, width: Int, height: Int) {
        previewSurface = surface; previewW = width; previewH = height
        updateActiveSurface()
    }

    // —— 模式切换 ——

    private fun setMode(next: Mode) {
        mode = next
        if (next == Mode.Home) {
            homeView.visibility = View.VISIBLE
            fullscreenSurface.visibility = View.GONE
            dotController.hide()
            showSystemBars()
        } else {
            homeView.visibility = View.GONE
            fullscreenSurface.visibility = View.VISIBLE
            enterFullscreen()
            dotController.show()
        }
        updateActiveSurface()
    }

    // 按当前模式把活动的 Surface 交给 Service(另一个不可见的 surface 已销毁/置空)。
    private fun updateActiveSurface() {
        if (mode == Mode.Home) {
            CarPlayService.attachSurface(previewSurface, previewW, previewH)
        } else {
            CarPlayService.attachSurface(fsSurface, fsW, fsH)
        }
    }

    // —— Service 状态(主线程)——

    override fun onStatus(status: ConnectionStatus) {
        control()?.render(status)
        preview()?.render(status)
        // 投屏中画面中断 → 退回主页(未投屏不停留全屏)。
        if (mode == Mode.Projection && status.phase != ConnectionStatus.Phase.Connected) setMode(Mode.Home)
        updateActiveSurface()
    }

    override fun onPhase(detail: String) { control()?.renderDetail(detail) }
    override fun onLog(line: String) = Unit  // 日志由 LogFragment 自行订阅 LinkLog

    private fun control() = supportFragmentManager.findFragmentById(R.id.control_container) as? ControlFragment
    private fun preview() = supportFragmentManager.findFragmentById(R.id.preview_container) as? PreviewFragment

    // —— 生命周期 / 全屏 Surface ——

    override fun onResume() {
        super.onResume()
        CarPlayService.statusListener = this
        onStatus(CarPlayService.connectionStatus)
        if (mode == Mode.Projection) dotController.show()
    }

    override fun onPause() {
        if (CarPlayService.statusListener === this) CarPlayService.statusListener = null
        dotController.hide()
        super.onPause()
    }

    override fun onDestroy() {
        if (CarPlayService.statusListener === this) CarPlayService.statusListener = null
        dotController.hide()
        inputForwarder.release()
        CarPlayService.attachSurface(null, 0, 0)
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        fsSurface = holder.surface; fsW = width; fsH = height
        updateActiveSurface()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        fsSurface = null
        updateActiveSurface()
    }

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

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
