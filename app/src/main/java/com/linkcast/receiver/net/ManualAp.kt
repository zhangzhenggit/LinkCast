package com.linkcast.receiver.net

/**
 * 调试用的静态凭据后端:自身不开启任何 AP,只提供一组固定 SSID/密码,
 * 由上层广告给 iPhone,使其连接到一个外部已开好的 AP(例如调试时手动起的指定频段热点),
 * 便于在已知频段下做连接对照。
 *
 * 当前仅保留骨架,选择优先级与设置页开关等外部接入逻辑后续再补。
 */
class ManualAp(
    private val ssid: String,
    private val passphrase: String,
) {
    /** 返回固定凭据 (ssid, passphrase),供上层发布给原生层。 */
    fun credentials(): Pair<String, String> = ssid to passphrase
}
