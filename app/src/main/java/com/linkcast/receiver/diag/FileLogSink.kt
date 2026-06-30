package com.linkcast.receiver.diag

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把日志追加写入文件,便于在屏蔽 logcat 的 ROM 上取日志(也为后续 App 内日志页打基础)。
 * 超过 [maxBytes] 时简单滚动(清空重写),避免无限增长。
 */
class FileLogSink(private val file: File, private val maxBytes: Long = 2_000_000) : LinkLog.Sink {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    override fun onLog(level: LinkLog.Level, tag: String, message: String) {
        runCatching {
            if (file.length() > maxBytes) file.writeText("")
            file.appendText("${fmt.format(Date())} ${level.name}/$tag: $message\n")
        }
    }
}
