package com.linkcast.receiver.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.linkcast.receiver.ConnectionStatus

/**
 * 预览区:实时画面 SurfaceView + 状态占位词。出图时显示画面(点击进全屏),否则隐藏画面、显示
 * 简洁状态词(未连接/连接中/已断开)。Surface 生命周期上报给宿主([LinkHost.onPreviewSurface])。
 */
class PreviewFragment : Fragment(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusWord: TextView
    private val host get() = activity as? LinkHost

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        statusWord = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(0xffb8c0cc.toInt())
            textSize = 15f
            text = "未连接"
        }
        surfaceView = SurfaceView(ctx).apply {
            holder.addCallback(this@PreviewFragment)
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_UP && host?.isStreaming == true) host?.requestProjection()
                true
            }
        }
        return FrameLayout(ctx).apply {
            setBackgroundColor(0xff1c1f26.toInt())
            addView(statusWord, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(surfaceView, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    override fun onResume() {
        super.onResume()
        render(com.linkcast.receiver.CarPlayService.connectionStatus)
    }

    /** 出图才显示画面;否则隐藏 SurfaceView 露出状态词(不留定格帧)。 */
    fun render(status: ConnectionStatus) {
        if (view == null) return
        statusWord.text = previewWord(status.phase)
        surfaceView.visibility = if (status.phase == ConnectionStatus.Phase.Connected) View.VISIBLE else View.INVISIBLE
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        host?.onPreviewSurface(holder.surface, width, height)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { host?.onPreviewSurface(null, 0, 0) }

    private fun previewWord(phase: ConnectionStatus.Phase): String = when (phase) {
        ConnectionStatus.Phase.Off -> "未连接"
        ConnectionStatus.Phase.Working -> "连接中"
        ConnectionStatus.Phase.Paused -> "已断开"
        ConnectionStatus.Phase.Connected -> ""
    }

    private companion object { const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT }
}
