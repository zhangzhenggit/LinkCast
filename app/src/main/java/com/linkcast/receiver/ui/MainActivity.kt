package com.linkcast.receiver.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
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
import com.linkcast.receiver.ProjectionService
import com.linkcast.receiver.input.InputForwarder

class MainActivity : Activity(), TextureView.SurfaceTextureListener, ProjectionService.StatusListener {
    private val inputForwarder = InputForwarder()
    private lateinit var textureView: TextureView
    private var surface: Surface? = null
    private lateinit var phaseView: TextView
    private lateinit var logView: TextView
    private lateinit var connectButton: Button
    private lateinit var cancelButton: Button
    private lateinit var panel: LinearLayout
    private lateinit var toggleButton: Button

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullscreen()
        requestRuntimePermissions()

        textureView = TextureView(this).apply {
            surfaceTextureListener = this@MainActivity
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
            setOnClickListener { ProjectionService.connect(this@MainActivity) }
        }
        cancelButton = Button(this).apply {
            text = "取消连接"
            isEnabled = false
            setOnClickListener { ProjectionService.cancel(this@MainActivity) }
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
        // Small floating toggle to show/hide the panel — when projecting, the panel is
        // hidden so it doesn't block touches; tap this to bring controls/logs back.
        toggleButton = Button(this).apply {
            text = "≡"
            alpha = 0.5f
            setOnClickListener {
                panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xff111318.toInt())
            addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
            addView(toggleButton, FrameLayout.LayoutParams(120, 120, Gravity.TOP or Gravity.END))
        })

        // Foreground service up (no auto-connect); the user drives connect from the UI.
        ProjectionService.start(this)
    }

    override fun onResume() {
        super.onResume()
        ProjectionService.statusListener = this
        // SurfaceTexture 在切后台时不销毁;回前台若已存在则重新挂载到投屏。
        surface?.let { ProjectionService.attachSurface(it, textureView.width, textureView.height) }
        // Render the latest known phase immediately on (re)bind.
        onPhase(ProjectionService.lastPhase, ProjectionService.isConnecting)
    }

    override fun onPause() {
        if (ProjectionService.statusListener === this) ProjectionService.statusListener = null
        super.onPause()
    }

    // ProjectionService.StatusListener — always delivered on the main thread.
    override fun onPhase(phase: String, connecting: Boolean) {
        phaseView.text = "极连投屏 LinkCast — $phase"
        connectButton.isEnabled = !connecting
        cancelButton.isEnabled = connecting
        // Once projecting, auto-hide the panel so it doesn't block CarPlay touches; the
        // floating ≡ button stays to bring it back (and cancel). Show it again otherwise.
        panel.visibility = if (phase.contains("投屏")) View.GONE else View.VISIBLE
    }

    override fun onLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 10) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }

    override fun onDestroy() {
        if (ProjectionService.statusListener === this) ProjectionService.statusListener = null
        ProjectionService.attachSurface(null, 0, 0)
        surface?.release()
        surface = null
        super.onDestroy()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(texture)
        ProjectionService.attachSurface(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        ProjectionService.attachSurface(surface, width, height)
    }

    // 返回 false:切后台时保留 SurfaceTexture,使投屏画面流不中断,回前台无需重建。
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = false

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

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
