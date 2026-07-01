package com.linkcast.receiver.ui

import android.view.Surface

/**
 * Fragment 与宿主 Activity 的协作接口。Activity 持有"全屏/预览"切换与视频流归属(由 Service 拥有),
 * Fragment 通过此接口请求进入投屏、上报预览 Surface 生命周期。
 */
interface LinkHost {
    /** 当前是否在出图投屏(用于预览点击是否允许进全屏)。 */
    val isStreaming: Boolean

    /** 请求进入全屏投屏(仅在出图时生效)。 */
    fun requestProjection()

    /** 预览 SurfaceView 的 Surface 变化(创建/尺寸变化传非空,销毁传空)。 */
    fun onPreviewSurface(surface: Surface?, width: Int, height: Int)

    /** 日志浮窗当前是否显示。 */
    val isLogShown: Boolean

    /** 开/关日志浮窗(开=打开日志+浮窗;关=关闭日志+移除浮窗,不占主界面)。 */
    fun toggleLog()
}
