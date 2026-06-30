package com.linkcast.receiver.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 设置页占位。先把入口立起来,具体设置项(码型/音频通道/热点模式/日志/语音键映射等)后续设计。
 */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xff111318.toInt())
            addView(TextView(this@SettingsActivity).apply {
                text = "设置（开发中）"
                setTextColor(0xffffffff.toInt())
                textSize = 18f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        })
    }
}
