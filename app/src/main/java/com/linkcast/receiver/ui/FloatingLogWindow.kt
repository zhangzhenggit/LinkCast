package com.linkcast.receiver.ui

import android.app.Activity
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.linkcast.receiver.diag.LinkLog

/**
 * 悬浮日志窗:独立子窗口承载(合成在最上层,含全屏 CarPlay 之上),不占用主界面布局。
 *
 * 两态:展开(完整窗口,标题栏可拖动)/ 收起(拖到边缘外松手 → 整窗收成边缘小把手,点把手再展开)。
 * `FLAG_NOT_FOCUSABLE` 使窗口外的触摸照常透传给 CarPlay。关闭(×)回调宿主统一处理。
 */
class FloatingLogWindow(
    private val activity: Activity,
    private val onClose: () -> Unit,
) {
    private val wm = activity.windowManager
    private val density = activity.resources.displayMetrics.density
    private val screenW = activity.resources.displayMetrics.widthPixels
    private val screenH = activity.resources.displayMetrics.heightPixels

    private val fullW = (screenW * 0.42f).toInt()
    private val fullH = (screenH * 0.5f).toInt()
    private val handleW = dp(30)
    private val handleH = dp(120)

    private val lines = ArrayDeque<String>()
    private var added = false
    private var collapsed = false
    private var onLeftEdge = false

    private val logBody = TextView(activity).apply {
        textSize = 10f; setTextColor(0xffb8c0cc.toInt()); setPadding(dp(10), dp(6), dp(10), dp(10))
    }
    private val logScroll = ScrollView(activity).apply { addView(logBody) }

    // 完整内容:标题栏 + 滚动日志。
    private val fullContent = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xf015171c.toInt())
        addView(buildTitleBar())
        addView(logScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    // 收起后的边缘把手:窄条,点击展开。
    private val handle = TextView(activity).apply {
        text = "日\n志"; gravity = Gravity.CENTER; textSize = 11f; setTextColor(0xffffffff.toInt())
        background = GradientDrawable().apply { setColor(0xdd20242c.toInt()); cornerRadius = dp(6).toFloat() }
        visibility = View.GONE
        setOnClickListener { expand() }
    }

    private val root = FrameLayout(activity).apply {
        addView(fullContent, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(handle, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private val lp = WindowManager.LayoutParams(
        fullW, fullH,
        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12); y = dp(12)
    }

    private val sink = LinkLog.Sink { _, tag, message ->
        logBody.post {
            lines.addLast("$tag: $message")
            while (lines.size > MAX_LINES) lines.removeFirst()
            logBody.text = lines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    val isShown: Boolean get() = added

    fun show() {
        if (added) return
        lp.token = activity.window.decorView.windowToken
        expandState()  // 每次打开都以完整态显示
        runCatching { wm.addView(root, lp) }.onSuccess { added = true }
        LinkLog.addSink(sink)
    }

    fun hide() {
        LinkLog.removeSink(sink)
        if (!added) return
        runCatching { wm.removeView(root) }
        added = false
    }

    // 收起成边缘把手(整窗缩小到窄条,贴指定边)。
    private fun collapse(left: Boolean) {
        collapsed = true; onLeftEdge = left
        fullContent.visibility = View.GONE
        handle.visibility = View.VISIBLE
        lp.width = handleW; lp.height = handleH
        lp.x = if (left) 0 else screenW - handleW
        lp.y = lp.y.coerceIn(0, screenH - handleH)
        apply()
    }

    private fun expand() { expandState(); apply() }

    private fun expandState() {
        collapsed = false
        fullContent.visibility = View.VISIBLE
        handle.visibility = View.GONE
        lp.width = fullW; lp.height = fullH
        lp.x = if (onLeftEdge) dp(4) else (screenW - fullW - dp(4)).coerceAtLeast(dp(4))
        lp.y = lp.y.coerceIn(0, (screenH - fullH).coerceAtLeast(0))
    }

    private fun buildTitleBar(): View {
        val title = TextView(activity).apply {
            text = "日志"; setTextColor(0xffffffff.toInt()); textSize = 13f
            setPadding(dp(10), dp(6), dp(6), dp(6))
        }
        // 直接贴边:收到离当前窗口更近的那条边,无需拖动。
        val dock = Button(activity).apply { text = "贴边"; setOnClickListener { collapse(left = lp.x + fullW / 2 < screenW / 2) } }
        val clear = Button(activity).apply { text = "清空"; setOnClickListener { lines.clear(); logBody.text = "" } }
        val close = Button(activity).apply { text = "×"; setOnClickListener { onClose() } }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xff20242c.toInt())
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(dock); addView(clear); addView(close)
            setOnTouchListener(DragListener())
        }
    }

    // 拖动标题栏移动窗口(仅位移,松手回收到屏内)。收起改为标题栏「贴边」按钮显式触发。
    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (e.rawX - downX)).toInt().coerceIn(dp(4), screenW - fullW - dp(4))
                    lp.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, screenH - fullH)
                    apply()
                }
            }
            return true
        }
    }

    private fun apply() { if (added) runCatching { wm.updateViewLayout(root, lp) } }

    private fun dp(v: Int): Int = (v * density).toInt()

    private companion object {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MAX_LINES = 300
    }
}
