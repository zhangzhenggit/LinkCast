package com.linkcast.receiver.auth

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.autoservice.carplay.CarplayNative
import com.linkcast.receiver.AuthProvider
import com.linkcast.receiver.ReceiverConfig
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

    companion object {
        private const val TAG = "Network MFI"
        private const val DEFAULT_HOST = "www.iamonroad.com"
        // Identity the paid MFi service is keyed to. Android 8+ scopes ANDROID_ID per
        // signing key, so this app's own ANDROID_ID is not the registered one; reuse
        // the companion app's ANDROID_ID so the service recognises the entitlement.
        private const val COMPANION_ANDROID_ID = "C6A763914B504C1E"
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val SESSION_TIMEOUT_MS = 60_000
        private const val CHALLENGE_TIMEOUT_MS = 5_000
        // While queued, the server streams status frames periodically; allow a long
        // per-frame and overall wait so we hold our queue slot instead of reconnecting.
        private const val QUEUE_WAIT_TIMEOUT_MS = 30_000
        private const val QUEUE_TOTAL_TIMEOUT_MS = 120_000L
        private const val QUEUE_RETRY_DELAY_MS = 1_500L
        private const val MAX_QUEUE_RETRIES = 40
        private const val ORIGINAL_VERSION_CODE = 297L
        private val DEFAULT_PORTS = intArrayOf(1522, 1525, 1500, 1505, 1510, 1515, 1550, 1551, 1552, 1553)

        fun open(context: Context, config: ReceiverConfig, listener: (String) -> Unit): NetworkMfiAuthProvider? {
            val host = config.networkMfiHost.ifBlank { DEFAULT_HOST }
            val configuredPorts = config.networkMfiPorts
            val ports = if (configuredPorts.isNotEmpty()) configuredPorts else DEFAULT_PORTS
            val firstPortIndex = config.networkMfiPortIndex.takeIf { it in ports.indices }
                ?: startingPortIndex(context, ports.size)
            val payload = buildClientPayload(context, config)
            var queueRetries = 0
            while (queueRetries <= MAX_QUEUE_RETRIES) {
                repeat(ports.size) { attempt ->
                    val portIndex = (firstPortIndex + attempt) % ports.size
                    val port = ports[portIndex]
                    try {
                        val provider = connectAndReadCertificate(host, port, payload, listener)
                        config.networkMfiPortIndex = portIndex
                        return provider
                    } catch (queued: NetworkMfiQueued) {
                        listener("$TAG queued on port:$port, ${queued.messageText}")
                        queueRetries += 1
                        Thread.sleep(QUEUE_RETRY_DELAY_MS)
                        return@repeat
                    } catch (error: Exception) {
                        listener("$TAG Error port:$port,$error")
                    }
                    Thread.sleep(500L)
                }
            }
            listener("$TAG unavailable after queue retries=$queueRetries")
            return null
        }

        private fun connectAndReadCertificate(
            host: String,
            port: Int,
            payload: String,
            listener: (String) -> Unit,
        ): NetworkMfiAuthProvider {
            val socket = Socket()
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
                    0x63 -> {
                        val status = input.readExact(certHeader.length).toString(StandardCharsets.UTF_8)
                        listener("MFI queue: $status")
                    }
                    0x62 -> {
                        val message = input.readExact(certHeader.length).toString(StandardCharsets.UTF_8)
                        runCatching { output.close() }; runCatching { input.close() }; runCatching { socket.close() }
                        throw NetworkMfiQueued(message)
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

        private fun buildClientPayload(context: Context, config: ReceiverConfig): String {
            val locale = Locale.getDefault()
            val localeTag = "${locale.language}_${locale.country}"
            val androidId = COMPANION_ANDROID_ID.ifBlank {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?.uppercase(Locale.ROOT)
                    .orEmpty()
            }
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

        private fun startingPortIndex(context: Context, portCount: Int): Int {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
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

private class NetworkMfiQueued(val messageText: String) : Exception(messageText)

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
