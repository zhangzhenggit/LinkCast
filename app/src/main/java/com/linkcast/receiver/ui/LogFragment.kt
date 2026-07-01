package com.linkcast.receiver.ui

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
import com.linkcast.receiver.LinkConfig
import com.linkcast.receiver.diag.LinkLog

/**
 * 日志区:独立滚动日志 + 总开关/折叠/清空。
 * "日志:开/关" = 总开关(LinkLog.enabled,持久化 diag_log)→ 关则全停输出;"折叠"仅收起视图。
 */
class LogFragment : Fragment() {
    private lateinit var config: LinkConfig
    private lateinit var logSwitch: Button
    private lateinit var logCollapse: Button
    private lateinit var logScroll: ScrollView
    private lateinit var logBody: TextView

    private var collapsed = false
    private val lines = ArrayDeque<String>()

    private val sink = LinkLog.Sink { _, tag, message ->
        val v = view ?: return@Sink
        v.post {
            lines.addLast("$tag: $message")
            while (lines.size > MAX_LINES) lines.removeFirst()
            logBody.text = lines.joinToString("\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        config = LinkConfig(ctx)
        logCollapse = Button(ctx).apply { setOnClickListener { toggleCollapse() } }
        logSwitch = Button(ctx).apply { setOnClickListener { toggleEnabled() } }
        val clear = Button(ctx).apply { text = "清空"; setOnClickListener { lines.clear(); logBody.text = "" } }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(logCollapse, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(logSwitch); addView(clear)
        }
        logBody = TextView(ctx).apply { textSize = 10f; setTextColor(0xff9aa6b2.toInt()); setPadding(dp(16), 0, dp(16), dp(12)) }
        logScroll = ScrollView(ctx).apply { addView(logBody) }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff15171c.toInt())
            addView(header)
            addView(logScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    override fun onResume() { super.onResume(); LinkLog.addSink(sink); updateControls() }
    override fun onPause() { LinkLog.removeSink(sink); super.onPause() }

    private fun toggleCollapse() {
        collapsed = !collapsed
        logScroll.visibility = if (collapsed) View.GONE else View.VISIBLE
        updateControls()
    }

    private fun toggleEnabled() {
        val on = !config.diagLogEnabled
        config.diagLogEnabled = on
        LinkLog.enabled = on
        if (!on) { lines.clear(); logBody.text = "日志已关闭" }
        updateControls()
    }

    private fun updateControls() {
        logCollapse.text = if (collapsed) "▸ 日志" else "▾ 日志"
        logSwitch.text = if (config.diagLogEnabled) "日志:开" else "日志:关"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val MAX_LINES = 200
    }
}
