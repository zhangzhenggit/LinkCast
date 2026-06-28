package com.linkcast.receiver

// 当前投屏分辨率(CarPlay 协商使用的尺寸),作为触控坐标换算等的共享真源。
object ProjectionMetrics {

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    fun update(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            this.width = width
            this.height = height
        }
    }
}
