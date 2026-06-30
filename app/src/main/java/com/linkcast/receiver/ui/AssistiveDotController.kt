package com.linkcast.receiver.ui

import android.app.Activity
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * 悬浮小白点(AssistiveTouch 式)。用独立子窗口承载,确保合成在全屏视频 SurfaceView 之上
 * (同窗口内会被视频 surface 盖住)。行为:
 * - 可自由拖动,松手停留原地;
 * - 空闲 [IDLE_MS] 后自动滑向最近边、半隐(露一半)并变暗;
 * - 触摸半隐状态先唤回(完整露出);
 * - 未拖动、且非"唤回"的单击 → 返回([onReturn])。
 *
 * 自管窗口与状态,宿主只需 [show]/[hide]。
 */
class AssistiveDotController(
    private val activity: Activity,
    private val onReturn: () -> Unit,
) {
    private val wm = activity.windowManager
    private val density = activity.resources.displayMetrics.density
    private val screenW = activity.resources.displayMetrics.widthPixels
    private val screenH = activity.resources.displayMetrics.heightPixels
    private val size = dp(40)
    private val margin = dp(8)
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop

    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { tuck() }

    private var added = false
    private var tucked = false

    // 拖动时窗口位置更新合并到每帧一次(对齐 vsync),避免逐事件 updateViewLayout 过量导致拖动发涩。
    private val choreographer = Choreographer.getInstance()
    private var pendingX = 0
    private var pendingY = 0
    private var frameScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        lp.x = pendingX; lp.y = pendingY
        update()
    }

    private val lp = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = screenW - size - margin
        y = (screenH * 0.18f).toInt()
    }

    private val dot = View(activity).apply {
        background = buildBackground()
        elevation = dp(4).toFloat()
        setOnTouchListener(DotTouch())
    }

    fun show() {
        if (added) return
        lp.token = activity.window.decorView.windowToken
        lp.x = screenW - size - margin
        lp.y = lp.y.coerceIn(0, screenH - size)
        dot.alpha = 1f
        tucked = false
        runCatching { wm.addView(dot, lp) }.onSuccess { added = true }
        scheduleIdle()
    }

    fun hide() {
        cancelIdle()
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        if (!added) return
        runCatching { wm.removeView(dot) }
        added = false
    }

    private inner class DotTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var wasTucked = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelIdle()
                    wasTucked = tucked
                    if (tucked) restore()
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragging = true
                    if (dragging) {
                        // 只记录目标位置,实际更新在下一帧合并执行。
                        pendingX = (startX + dx).toInt().coerceIn(0, screenW - size)
                        pendingY = (startY + dy).toInt().coerceIn(0, screenH - size)
                        if (!frameScheduled) { frameScheduled = true; choreographer.postFrameCallback(frameCallback) }
                    }
                }
                // 拖动后停留并重新计时;未拖动:若是从半隐唤回则仅停留,否则视为单击返回。
                MotionEvent.ACTION_UP -> if (!dragging && !wasTucked) onReturn() else scheduleIdle()
                MotionEvent.ACTION_CANCEL -> scheduleIdle()
            }
            return true
        }
    }

    // 滑向最近边、半隐 + 变暗。
    private fun tuck() {
        if (!added) return
        val center = lp.x + size / 2
        lp.x = if (center < screenW / 2) -size / 2 else screenW - size / 2
        dot.alpha = 0.4f
        tucked = true
        update()
    }

    // 唤回:完整露出最近边、恢复不透明。
    private fun restore() {
        val onLeft = lp.x < screenW / 2
        lp.x = if (onLeft) margin else screenW - size - margin
        dot.alpha = 1f
        tucked = false
        update()
    }

    private fun update() { if (added) runCatching { wm.updateViewLayout(dot, lp) } }
    private fun scheduleIdle() { cancelIdle(); handler.postDelayed(idleRunnable, IDLE_MS) }
    private fun cancelIdle() { handler.removeCallbacks(idleRunnable) }

    // 深色半透明圆 + 极淡内圈(读起来像按钮),无外白边。
    private fun buildBackground(): LayerDrawable {
        val outer = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xb3202428.toInt()) }
        val inner = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33ffffff) }
        return LayerDrawable(arrayOf(outer, InsetDrawable(inner, dp(12))))
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private companion object { const val IDLE_MS = 2500L }
}
