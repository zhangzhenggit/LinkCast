package com.linkcast.receiver.net

import com.linkcast.receiver.Endpoints
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远端配置:签名服务器地址与端口。
 *
 * 配置为 key=value 文本,取其中的 host_ip 与 host_ports。每个进程仅拉取一次,结果缓存在内存;
 * 进程重启后重新拉取。不写本地、不使用过期的内置默认值——拉取失败则 [host] 为空,由调用方决定如何处理。
 */
object RemoteConfig {

    private const val KEY_HOST = "host_ip"
    private const val KEY_PORTS = "host_ports"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_BODY_BYTES = 64 * 1024

    @Volatile
    private var loaded = false

    @Volatile
    var host: String? = null
        private set

    @Volatile
    var ports: IntArray = IntArray(0)
        private set

    /**
     * 拉取并解析远端配置。进程内只成功加载一次;之后调用直接返回。
     * 该方法发起网络请求且为阻塞调用,必须在后台线程执行。
     */
    @Synchronized
    fun ensureLoaded(log: (String) -> Unit = {}) {
        if (loaded) return

        val body = runCatching { download(Endpoints.CONFIG_URL) }.getOrElse {
            log("RemoteConfig 拉取失败: $it")
            return
        }

        val entries = parse(body)
        val parsedHost = entries[KEY_HOST]?.takeIf { it.isNotBlank() }
        val parsedPorts = entries[KEY_PORTS].orEmpty()
            .split(",", ";", "|")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..65535 }
            .toIntArray()
        if (parsedHost == null || parsedPorts.isEmpty()) {
            log("RemoteConfig 配置缺少 host_ip 或 host_ports")
            return
        }

        host = parsedHost
        ports = parsedPorts
        loaded = true
        log("RemoteConfig 已加载 host=$parsedHost ports=${parsedPorts.size}")
    }

    private fun download(urlText: String): String {
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
            return connection.inputStream.use { readBounded(it, MAX_BODY_BYTES) }.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) error("配置文件超过 $max 字节")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun parse(body: String): Map<String, String> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
            .toMap()
}
