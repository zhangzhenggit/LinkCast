package com.linkcast.receiver.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.linkcast.receiver.CarPlayService
import com.linkcast.receiver.diag.LinkLog
import com.linkcast.receiver.input.InputForwarder

class MainActivity : Activity(), SurfaceHolder.Callback, CarPlayService.StatusListener {
    private val inputForwarder = InputForwarder()
    private lateinit var surfaceView: SurfaceView
    private lateinit var phaseView: TextView
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var cancelButton: Button
    private lateinit var panel: LinearLayout
    private lateinit var toggleButton: Button

    private val logLines = ArrayDeque<String>()

    private companion object {
        const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullscreen()
        requestRuntimePermissions()

        // 用 SurfaceView 渲染投屏:硬件 overlay 直出,性能/清晰度/流畅度最佳。切后台 surface 销毁
        // 不丢解码器(VideoPipeline 切到占位 Surface 保活),回前台 surfaceChanged 重新挂回即可。
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@MainActivity)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { view, event -> inputForwarder.onTouch(view, event) }
        }

        phaseView = TextView(this).apply {
            text = "极连投屏 LinkCast — 空闲"
            setTextColor(0xffffffff.toInt())
            textSize = 16f
        }
        logView = TextView(this).apply {
            setTextColor(0xffb8c0cc.toInt())
            textSize = 11f
            setPadding(0, 12, 0, 0)
        }
        connectButton = Button(this).apply {
            text = "开始连接"
            setOnClickListener { CarPlayService.connect(this@MainActivity) }
        }
        cancelButton = Button(this).apply {
            text = "取消连接"
            isEnabled = false
            setOnClickListener { CarPlayService.cancel(this@MainActivity) }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(connectButton)
            addView(cancelButton)
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 18, 24, 18)
            addView(phaseView)
            addView(buttonRow)
            addView(logView)
        }
        // 投屏时隐藏控制面板避免遮挡触控;点这个浮动按钮可重新唤出面板(含取消)。
        toggleButton = Button(this).apply {
            text = "≡"
            alpha = 0.5f
            setOnClickListener {
                panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xff111318.toInt())
            addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
            addView(toggleButton, FrameLayout.LayoutParams(120, 120, Gravity.TOP or Gravity.END))
        })

        // 启动前台服务(不自动连接),由用户在界面上发起连接。
        CarPlayService.start(this)
    }

    override fun onResume() {
        super.onResume()
        CarPlayService.statusListener = this
        // 立即刷新当前阶段(SurfaceView 的挂载由 surfaceChanged 处理,这里不动 surface)。
        onPhase(CarPlayService.lastPhase, CarPlayService.isConnecting)
    }

    override fun onPause() {
        if (CarPlayService.statusListener === this) CarPlayService.statusListener = null
        super.onPause()
    }

    // CarPlayService.StatusListener —— 始终在主线程回调。
    override fun onPhase(phase: String, connecting: Boolean) {
        phaseView.text = "极连投屏 LinkCast — $phase"
        connectButton.isEnabled = !connecting
        cancelButton.isEnabled = connecting
        panel.visibility = if (phase.contains("投屏")) View.GONE else View.VISIBLE
    }

    override fun onLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 10) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }

    override fun onDestroy() {
        if (CarPlayService.statusListener === this) CarPlayService.statusListener = null
        CarPlayService.attachSurface(null, 0, 0)
        super.onDestroy()
    }

    // SurfaceHolder.Callback:surface 由系统创建/销毁。销毁时交出 null,VideoPipeline 切到占位
    // Surface 保活;尺寸就绪(surfaceChanged)时挂回真实 surface。
    override fun surfaceCreated(holder: SurfaceHolder) {
        LinkLog.d(TAG) { "surfaceCreated" }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        LinkLog.d(TAG) { "surfaceChanged ${width}x$height valid=${holder.surface?.isValid}" }
        CarPlayService.attachSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        LinkLog.d(TAG) { "surfaceDestroyed" }
        CarPlayService.attachSurface(null, 0, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return inputForwarder.onKey(event) || super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    // 沉浸式全屏:铺满刘海/挖孔区,隐藏状态栏与导航栏(上滑临时唤出)。
    private fun enterFullscreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 10)
        }
    }
}
