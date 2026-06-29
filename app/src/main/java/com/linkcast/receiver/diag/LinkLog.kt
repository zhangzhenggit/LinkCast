package com.linkcast.receiver.diag

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局调试日志出口。项目内所有自定义日志统一经此发出,以便一处开关、统一接入展示端。
 *
 * 消息以 lambda 传入:总开关关闭时不拼接字符串、不写 logcat、不分发,热路径零开销。
 * 展示端(如后续的浮窗日志页)实现 [Sink] 订阅即可,无需改动各调用点。
 */
object LinkLog {

    enum class Level { D, I, W, E }

    fun interface Sink {
        fun onLog(level: Level, tag: String, message: String)
    }

    // 日志总开关,运行时可切。
    @Volatile
    var enabled: Boolean = true

    private val sinks = CopyOnWriteArrayList<Sink>()

    fun addSink(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    inline fun d(tag: String, message: () -> String) {
        if (enabled) emit(Level.D, tag, message())
    }

    inline fun i(tag: String, message: () -> String) {
        if (enabled) emit(Level.I, tag, message())
    }

    inline fun w(tag: String, message: () -> String) {
        if (enabled) emit(Level.W, tag, message())
    }

    inline fun e(tag: String, message: () -> String) {
        if (enabled) emit(Level.E, tag, message())
    }

    // 供内联函数调用,故为 public;调用方请用上面的分级方法,不要直接调它。
    fun emit(level: Level, tag: String, message: String) {
        when (level) {
            Level.D -> Log.d(tag, message)
            Level.I -> Log.i(tag, message)
            Level.W -> Log.w(tag, message)
            Level.E -> Log.e(tag, message)
        }
        sinks.forEach { runCatching { it.onLog(level, tag, message) } }
    }
}
