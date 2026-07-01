package com.linkcast.receiver.input

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.example.autoservice.carplay.CarplayNative
import com.linkcast.receiver.ProjectionMetrics
import java.util.concurrent.Executors

/**
 * 把屏上触控/按键转发给 CarPlay。
 *
 * 坐标在 UI 线程换算,[CarplayNative.onTouchEvent] 的 JNI 转发交给专用后台线程执行(与原版一致):
 * 该调用内部要把坐标经控制通道发给 iPhone,通道背压时可能阻塞,不应压在 UI 线程上。单线程顺序
 * 执行保持事件先后。每帧发一个点即可(触摸发送通道有限速,发历史采样点全量反而打爆通道)。
 */
class InputForwarder {
    private val touchDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "linkcast-touch").apply { isDaemon = true }
    }

    fun onTouch(view: View, event: MotionEvent): Boolean {
        val projWidth = ProjectionMetrics.width
        val projHeight = ProjectionMetrics.height
        if (projWidth <= 0 || projHeight <= 0) return false
        // 触控坐标按当前投屏分辨率换算(MotionEvent 须在 UI 线程读取)。
        val x = ((event.x / view.width.coerceAtLeast(1)) * projWidth).toInt()
        val y = ((event.y / view.height.coerceAtLeast(1)) * projHeight).toInt()
        // 按下/移动 pressed=1,抬起/取消 pressed=0。
        val pressed = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> 1
            else -> 0
        }
        runCatching { touchDispatcher.execute { CarplayNative.onTouchEvent(x, y, pressed) } }
        return true
    }

    /** 释放后台线程,宿主销毁时调用。 */
    fun release() {
        touchDispatcher.shutdown()
    }

    fun onKey(event: KeyEvent): Boolean {
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> 8
            KeyEvent.KEYCODE_MEDIA_PAUSE -> 8
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> 8
            KeyEvent.KEYCODE_MEDIA_NEXT -> 3
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> 4
            KeyEvent.KEYCODE_BACK -> 2
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> 5
            KeyEvent.KEYCODE_DPAD_UP -> 12
            KeyEvent.KEYCODE_DPAD_DOWN -> 13
            KeyEvent.KEYCODE_DPAD_LEFT -> 14
            KeyEvent.KEYCODE_DPAD_RIGHT -> 15
            else -> return false
        }
        CarplayNative.onKeyEvent(mapped, event.action)
        return true
    }
}
