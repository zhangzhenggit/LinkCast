package com.linkcast.receiver.auth

import android.content.Context
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.autoservice.carplay.CarplayNative
import com.linkcast.receiver.AuthProvider
import com.linkcast.receiver.LinkConfig
import com.linkcast.receiver.net.RemoteConfig
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale

class NetworkMfiAuthProvider private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    override val certificate: ByteArray,
    private val listener: (String) -> Unit,
) : AuthProvider {
    override val canSign: Boolean = true

    /**
     * 取消句柄:[open] 在后台线程阻塞(连接/排队等待)时,持有其 socket;调用 [cancel] 关闭 socket
     * 让阻塞的读/连接立即抛异常退出。Java 的 socket 阻塞读 interrupt 打不断,只能靠 close。
     */
    class Canceller {
        @Volatile private var socket: Socket? = null
        private var canceled = false

        @Synchronized fun attach(s: Socket) {
            if (canceled) runCatching { s.close() } else socket = s
        }

        @Synchronized fun cancel() {
            canceled = true
            runCatching { socket?.close() }
            socket = null
        }
    }

    companion object {
        private const val TAG = "Network MFI"
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val SESSION_TIMEOUT_MS = 60_000
        private const val CHALLENGE_TIMEOUT_MS = 5_000
        // 排队期间服务器周期性推送状态帧(0x63);单条连接上等待,不重连(重连只会重新排队)。
        private const val QUEUE_WAIT_TIMEOUT_MS = 30_000
        private const val QUEUE_TOTAL_TIMEOUT_MS = 120_000L
        private const val ORIGINAL_VERSION_CODE = 297L

        /**
         * 向签名服务器申请证书。返回非空 = 成功(可签名)。
         * 服务端终态裁决(0x62:到期/未授权/名额满)以 [NetworkMfiRejected] 上抛 —— 不重试,由上层暂停;
         * 其它失败(连不上/排队超时等)返回 null,视为暂时不可用。
         */
        fun open(
            context: Context,
            config: LinkConfig,
            canceller: Canceller,
            listener: (String) -> Unit,
        ): NetworkMfiAuthProvider? {
            RemoteConfig.ensureLoaded(listener)
            val host = RemoteConfig.host
            val ports = RemoteConfig.ports
            if (host.isNullOrBlank() || ports.isEmpty()) {
                listener("$TAG 无可用签名服务器配置,放弃连接")
                return null
            }
            val port = ports[portIndexFor(context, ports.size)]
            val payload = buildClientPayload(context, config)
            return try {
                connectAndReadCertificate(host, port, payload, canceller, listener)
            } catch (rejected: NetworkMfiRejected) {
                // 终态裁决:不吞掉,上抛给调用方据此暂停(认证被拒)。
                throw rejected
            } catch (error: Exception) {
                listener("$TAG 连接失败 host=$host port=$port: $error")
                null
            }
        }

        private fun connectAndReadCertificate(
            host: String,
            port: Int,
            payload: String,
            canceller: Canceller,
            listener: (String) -> Unit,
        ): NetworkMfiAuthProvider {
            val socket = Socket()
            // 交给取消句柄,使外部可在连接/排队阻塞期间关闭它。
            canceller.attach(socket)
            socket.connect(InetSocketAddress(InetAddress.getByName(host), port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = socket.getInputStream()
            val header = input.readFrameHeader()
            listener("MFI connect read command=${header.command},N=4,port=$port,host=$host")
            if (header.command != 0x63) error("unexpected greeting command=${header.command}")
            val greeting = input.readExact(header.length).toString(StandardCharsets.UTF_8)
            listener("MFI greeting: $greeting")
            socket.soTimeout = QUEUE_WAIT_TIMEOUT_MS
            val output = socket.getOutputStream()
            output.writeFrame(0x01, payload.toByteArray(StandardCharsets.UTF_8))
            // Stay on this one connection and read frames until the certificate
            // (cmd 0x02) arrives. The free queue keeps streaming status frames
            // (cmd 0x63) "正在排队认证中…" until our turn; we must wait it out on the
            // SAME socket rather than reconnecting (a reconnect just re-queues).
            val deadline = SystemClock.elapsedRealtime() + QUEUE_TOTAL_TIMEOUT_MS
            while (true) {
                val certHeader = input.readFrameHeader()
                listener("MFI Recv 4: Command=${certHeader.command}, Len=${certHeader.length}")
                when (certHeader.command) {
                    0x02 -> {
                        val cert = input.readExact(certHeader.length)
                        listener("iAP2AuthReadCertData length ${cert.size}")
                        socket.soTimeout = SESSION_TIMEOUT_MS
                        return NetworkMfiAuthProvider(socket, input, output, cert, listener)
                    }
                    // 0x63 = 排队中(瞬时):服务器仍在处理,保持本连接继续等待。
                    0x63 -> {
                        val status = input.readExact(certHeader.length).toString(StandardCharsets.UTF_8)
                        listener("MFI 排队中: $status")
                    }
                    // 0x62 = 终态裁决(无证书):到期/未授权/名额满。对齐原版 —— 立即停、不重试。
                    0x62 -> {
                        val message = input.readExact(certHeader.length).toString(StandardCharsets.UTF_8)
                        listener("MFI 认证被拒(0x62): $message")
                        runCatching { output.close() }; runCatching { input.close() }; runCatching { socket.close() }
                        throw NetworkMfiRejected(message)
                    }
                    else -> error("unexpected certificate command=${certHeader.command}")
                }
                if (SystemClock.elapsedRealtime() > deadline) {
                    runCatching { socket.close() }
                    error("queue wait timed out")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("unreachable")
        }

        private fun buildClientPayload(context: Context, config: LinkConfig): String {
            val locale = Locale.getDefault()
            val localeTag = "${locale.language}_${locale.country}"
            val androidId = DeviceCredential.identity(context)
            return listOf(
                localeTag,
                androidId,
                ORIGINAL_VERSION_CODE.toString(),
                CarplayNative.setStatusBarEdge(0).orEmpty(),
                "0735",
                currentInterfaceDescription(context),
            ).joinToString(";")
        }

        private fun currentInterfaceDescription(context: Context): String {
            val display = context.getSystemService(WindowManager::class.java)?.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display?.getRealMetrics(metrics)
            val screen = "${metrics.widthPixels}x${metrics.heightPixels}"
            val iface = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .firstOrNull { it.isUp && !it.isLoopback && it.inetAddresses.toList().any { address -> !address.isLoopbackAddress } }
            val name = iface?.name.orEmpty()
            val mac = iface?.hardwareAddress?.joinToString(separator = "") { "%02x".format(it) }
                ?.takeIf { it.isNotBlank() && it != "020000000000" }
                ?: "02:00:00:00:00:00"
            return "$name@$mac;$screen"
        }

        // 按设备凭据散列出一个端口下标,使不同设备分散到不同端口。
        private fun portIndexFor(context: Context, portCount: Int): Int {
            val androidId = DeviceCredential.identity(context)
            return androidId.take(3).toIntOrNull(16)?.rem(portCount) ?: 0
        }
    }

    override fun respond(challenge: ByteArray, length: Int): ByteArray? {
        val payload = challenge.copyOf(length)
        synchronized(this) {
            return runCatching {
                socket.soTimeout = CHALLENGE_TIMEOUT_MS
                output.writeFrame(0x03, payload)
                val header = input.readFrameHeader()
                listener("MFI Recv 4: Command=${header.command}, Len=${header.length}")
                if (header.command != 0x04) return null
                val response = input.readExact(header.length)
                listener("MFI ChallengeResponse length ${response.size}")
                response
            }.onFailure { error ->
                listener("$TAG challenge failed: $error")
            }.getOrNull()
        }
    }

    override fun release() {
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { socket.close() }
    }
}

private data class NetworkMfiFrameHeader(val command: Int, val length: Int)

/** 签名服务器的终态裁决(0x62:试用到期/未授权/名额满)。不应重试,应据此暂停等待用户处理。 */
class NetworkMfiRejected(message: String) : Exception(message)

private fun InputStream.readFrameHeader(): NetworkMfiFrameHeader {
    val header = readExact(4)
    val command = header[0].toInt() and 0xff
    val length = ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
    return NetworkMfiFrameHeader(command, length)
}

private fun InputStream.readExact(length: Int): ByteArray {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(buffer, offset, length - offset)
        if (read < 0) error("stream closed at $offset/$length")
        offset += read
    }
    return buffer
}

private fun OutputStream.writeFrame(command: Int, payload: ByteArray) {
    val header = byteArrayOf(
        command.toByte(),
        0,
        ((payload.size ushr 8) and 0xff).toByte(),
        (payload.size and 0xff).toByte(),
    )
    write(header)
    write(payload)
    flush()
}
