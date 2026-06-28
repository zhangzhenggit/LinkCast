package com.linkcast.receiver

// 本地存储(SharedPreferences)名称,按用途区分。
object Prefs {
    // 应用配置:分辨率、音频、热点等。
    const val CONFIG = "linkcast_config"

    // 连接凭据:设备凭据身份、USB key 等。
    const val CREDENTIAL = "linkcast_credential"
}
