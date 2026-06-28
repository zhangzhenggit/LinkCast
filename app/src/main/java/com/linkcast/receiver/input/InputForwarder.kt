package com.linkcast.receiver.input

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.example.autoservice.carplay.CarplayNative
import com.linkcast.receiver.ProjectionMetrics

class InputForwarder {
    fun onTouch(view: View, event: MotionEvent): Boolean {
        val projWidth = ProjectionMetrics.width
        val projHeight = ProjectionMetrics.height
        if (projWidth <= 0 || projHeight <= 0) return false
        // 触控坐标按当前投屏分辨率换算
        val x = ((event.x / view.width.coerceAtLeast(1)) * projWidth).toInt()
        val y = ((event.y / view.height.coerceAtLeast(1)) * projHeight).toInt()
        // onTouchEvent(x, y, pressed):手指按下/移动时 pressed=1,抬起时 0
        val a = event.actionMasked
        val pressed = if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) 1 else 0
        CarplayNative.onTouchEvent(x, y, pressed)
        return true
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
