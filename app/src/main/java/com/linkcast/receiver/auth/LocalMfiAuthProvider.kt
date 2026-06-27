package com.linkcast.receiver.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.linkcast.receiver.AuthProvider
import com.linkcast.receiver.EmptyAuthProvider
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class LocalMfiAuthProvider private constructor(
    private val delegate: AuthProvider,
) : AuthProvider {
    override val certificate: ByteArray?
        get() = delegate.certificate

    override val canSign: Boolean
        get() = delegate.canSign

    override fun respond(challenge: ByteArray, length: Int): ByteArray? {
        return delegate.respond(challenge, length)
    }

    override fun release() {
        delegate.release()
    }

    companion object {
        private const val TAG = "LocalMfiAuth"
        private const val CERT_FILE = "cert.bin"
        const val ACTION_USB_PERMISSION = "com.android.usb.USB_PERMISSION"

        fun open(context: Context, listener: (String) -> Unit): LocalMfiAuthProvider {
            val cached = readCachedCertificate(context)
            val usb = UsbMfiAuthProvider.open(context, listener, cached)
            if (usb != null) {
                listener("Local MFI auth ready from USB device; certificate=${usb.certificate?.size ?: 0}")
                return LocalMfiAuthProvider(usb)
            }

            if (cached != null) {
                listener("Local MFI cert.bin present but USB signer is unavailable; certificate=${cached.size}")
                return LocalMfiAuthProvider(FileOnlyAuthProvider)
            }

            listener("Local MFI auth unavailable: missing cert.bin and no USB MFI device")
            return LocalMfiAuthProvider(EmptyAuthProvider)
        }

        private fun readCachedCertificate(context: Context): ByteArray? {
            val file = File(context.filesDir, CERT_FILE)
            if (file.isFile && file.length() in 2..4096) {
                return runCatching { file.readBytes() }.getOrNull()
            }
            return runCatching {
                context.assets.open(CERT_FILE).use { it.readBytes() }
            }.getOrNull()?.takeIf { it.size in 2..4096 }
        }
    }
}

private object FileOnlyAuthProvider : AuthProvider {
    override val certificate: ByteArray? = null
    override val canSign: Boolean = false
    override fun respond(challenge: ByteArray, length: Int): ByteArray? = null
    override fun release() = Unit
}

private class UsbMfiAuthProvider private constructor(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val certPath: File,
    cachedCertificate: ByteArray?,
    private val listener: (String) -> Unit,
) : AuthProvider {
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null
    private var deviceAddress: Byte = 0x22

    override var certificate: ByteArray? = cachedCertificate
        private set

    override val canSign: Boolean = true

    init {
        if (openConnection()) {
            certificate = readCertificate()
        }
    }

    override fun respond(challenge: ByteArray, length: Int): ByteArray? = synchronized(this) {
        val conn = connection ?: return null
        waitUntilReady(conn, 5, 10)
        SystemClock.sleep(10)
        listener("MFI ChallengeResponse req length=$length")

        val lengthBytes = byteArrayOf(((length ushr 8) and 0xff).toByte(), (length and 0xff).toByte())
        writeRegister(conn, 0x20, lengthBytes, 2)
        writeRegisterLarge(conn, 0x21, challenge, length)
        writeRegister(conn, 0x10, byteArrayOf(1), 1)
        SystemClock.sleep(400)

        val responseLengthBytes = byteArrayOf(0, 0)
        readRegister(conn, 0x11, responseLengthBytes, 0, 2)
        val responseLength = ((responseLengthBytes[0].toInt() and 0xff) shl 8) or
            (responseLengthBytes[1].toInt() and 0xff)
        if (responseLength <= 0 || responseLength > 256) return null

        val response = ByteArray(responseLength)
        readRegister(conn, 0x12, response, 0, responseLength)
        listener("MFI ChallengeResponse length=$responseLength")
        response
    }

    override fun release() {
        val conn = connection
        val intf = usbInterface
        if (conn != null && intf != null) {
            runCatching { conn.releaseInterface(intf) }
        }
        runCatching { conn?.close() }
        connection = null
    }

    private fun openConnection(): Boolean {
        repeat(2) {
            val conn = usbManager.openDevice(device) ?: return false
            connection = conn
            val config = device.getConfiguration(0)
            val intf = device.getInterface(0)
            usbInterface = intf
            conn.setConfiguration(config)
            conn.claimInterface(intf, true)
            for (index in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(index)
                when (endpoint.address) {
                    0x02 -> outEndpoint = endpoint
                    0x82 -> inEndpoint = endpoint
                }
            }

            deviceAddress = 0x22
            if (isReady(conn)) return true
            SystemClock.sleep(40)
            if (isReady(conn)) return true
            deviceAddress = 0x20
            if (isReady(conn)) return true
            SystemClock.sleep(40)
            if (isReady(conn)) return true
            release()
            SystemClock.sleep(100)
        }
        listener("MFI chip detection error")
        return false
    }

    private fun readCertificate(): ByteArray? {
        val conn = connection ?: return null
        val lengthBytes = byteArrayOf(0, 0)
        readRegister(conn, 0x30, lengthBytes, 0, 2)
        val length = ((lengthBytes[0].toInt() and 0xff) shl 8) or (lengthBytes[1].toInt() and 0xff)
        listener("iAP2AuthReadCertData Len=$length")
        if (length <= 1 || length > 1024) return null

        if (certPath.isFile && certPath.length() == length.toLong()) {
            runCatching { certPath.readBytes() }.getOrNull()?.let {
                rememberUkeyId(it)
                return it
            }
        }

        val cert = ByteArray(length)
        var offset = 0
        var register = 0x31
        while (offset + 128 <= length) {
            readRegister(conn, register, cert, offset, 128)
            offset += 128
            register += 1
        }
        if (offset < length) {
            readRegister(conn, register, cert, offset, length - offset)
        }
        runCatching { certPath.writeBytes(cert) }
        rememberUkeyId(cert)
        return cert
    }

    private fun rememberUkeyId(cert: ByteArray) {
        val digest = MessageDigest.getInstance("MD5").digest(cert)
        val ukeyId = "usb" + digest.joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
        context.getSharedPreferences("SP", Context.MODE_PRIVATE)
            .edit()
            .putString("ukeyid", ukeyId)
            .apply()
        listener("Local MFI ukey=$ukeyId")
    }

    private fun waitUntilReady(conn: UsbDeviceConnection, attempts: Int, delayMs: Long) {
        repeat(attempts) {
            if (isReady(conn)) return
            SystemClock.sleep(delayMs)
        }
    }

    private fun isReady(conn: UsbDeviceConnection): Boolean {
        val out = outEndpoint ?: return false
        val input = inEndpoint ?: return false
        conn.bulkTransfer(out, byteArrayOf(0xaa.toByte(), 0x74, 0x00), 3, 500)
        val probe = byteArrayOf(0xaa.toByte(), 0x80.toByte(), deviceAddress, 0x4c, 0x00)
        conn.bulkTransfer(out, probe, probe.size, 500)
        val status = byteArrayOf(0)
        conn.bulkTransfer(input, status, 1, 500)
        conn.bulkTransfer(out, byteArrayOf(0xaa.toByte(), 0x75, 0x00), 3, 500)
        return (status[0].toInt() and 0x80) == 0
    }

    private fun readRegister(conn: UsbDeviceConnection, register: Int, target: ByteArray, offset: Int, length: Int): Int {
        isReady(conn)
        SystemClock.sleep(15)
        val out = outEndpoint ?: return 0
        val input = inEndpoint ?: return 0
        val setup = byteArrayOf(
            0xaa.toByte(), 0x74, 0x81.toByte(), deviceAddress, 0x4a, 0x81.toByte(),
            register.toByte(), 0x75, 0x00
        )
        conn.bulkTransfer(out, setup, setup.size, 500)
        SystemClock.sleep(15)
        val one = byteArrayOf(0)
        for (index in 0 until length) {
            val readOne = byteArrayOf(
                0xaa.toByte(), 0x74, 0x81.toByte(), (deviceAddress.toInt() or 1).toByte(),
                0x52, 0xc0.toByte(), 0x75, 0x00
            )
            conn.bulkTransfer(out, readOne, readOne.size, 500)
            conn.bulkTransfer(input, one, 1, 500)
            target[offset + index] = one[0]
            Thread.sleep(0, 100_000)
        }
        if (length >= 2) {
            Log.d(TAG, "read reg ${register.toString(16)}::${target[offset].toUByte()}, ${target[offset + 1].toUByte()}")
        }
        return length
    }

    private fun writeRegister(conn: UsbDeviceConnection, register: Int, data: ByteArray, length: Int): Int {
        isReady(conn)
        SystemClock.sleep(5)
        val out = outEndpoint ?: return 0
        conn.bulkTransfer(
            out,
            byteArrayOf(0xaa.toByte(), 0x74, 0x81.toByte(), deviceAddress, 0x75, 0x00),
            6,
            500
        )
        SystemClock.sleep(5)
        val packet = ByteArray(length + 11)
        packet[0] = 0xaa.toByte()
        packet[1] = 0x74
        packet[2] = 0x81.toByte()
        packet[3] = deviceAddress
        packet[4] = 0x4a
        packet[5] = 0x81.toByte()
        packet[6] = register.toByte()
        packet[7] = 0x4a
        packet[8] = (length or 0x80).toByte()
        data.copyInto(packet, 9, 0, length)
        packet[length + 9] = 0x75
        packet[length + 10] = 0
        conn.bulkTransfer(out, packet, packet.size, 500)
        SystemClock.sleep(2)
        return 0
    }

    private fun writeRegisterLarge(conn: UsbDeviceConnection, register: Int, data: ByteArray, length: Int): Int {
        isReady(conn)
        SystemClock.sleep(5)
        val out = outEndpoint ?: return 0
        conn.bulkTransfer(
            out,
            byteArrayOf(0xaa.toByte(), 0x74, 0x81.toByte(), deviceAddress, 0x75, 0x00),
            6,
            500
        )
        SystemClock.sleep(5)
        val first = minOf(16, length)
        val firstPacket = ByteArray(first + 10)
        firstPacket[0] = 0xaa.toByte()
        firstPacket[1] = 0x74
        firstPacket[2] = 0x81.toByte()
        firstPacket[3] = deviceAddress
        firstPacket[4] = 0x4a
        firstPacket[5] = 0x81.toByte()
        firstPacket[6] = register.toByte()
        firstPacket[7] = 0x4a
        firstPacket[8] = (first or 0x80).toByte()
        data.copyInto(firstPacket, 9, 0, first)
        firstPacket[first + 9] = 0
        conn.bulkTransfer(out, firstPacket, firstPacket.size, 500)
        SystemClock.sleep(2)

        val rest = length - first
        val restPacket = ByteArray(rest + 4)
        restPacket[0] = 0xaa.toByte()
        restPacket[1] = (rest or 0x80).toByte()
        data.copyInto(restPacket, 2, first, length)
        restPacket[rest + 2] = 0x75
        restPacket[rest + 3] = 0
        conn.bulkTransfer(out, restPacket, restPacket.size, 500)
        SystemClock.sleep(2)
        return 0
    }

    companion object {
        private const val TAG = "UsbMfiAuth"
        private const val MFI_VENDOR_ID = 0x1a86
        private const val MFI_PRODUCT_ID = 0x5512
        fun open(context: Context, listener: (String) -> Unit, cachedCertificate: ByteArray?): UsbMfiAuthProvider? {
            val manager = context.getSystemService(UsbManager::class.java) ?: return null
            logVisibleUsbDevices(manager, listener)
            val device = manager.deviceList.values.firstOrNull {
                it.vendorId == MFI_VENDOR_ID && it.productId == MFI_PRODUCT_ID
            } ?: return null
            if (!manager.hasPermission(device)) {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val permissionIntent = Intent(LocalMfiAuthProvider.ACTION_USB_PERMISSION)
                permissionIntent.setPackage(context.packageName)
                val intent = PendingIntent.getBroadcast(
                    context,
                    0,
                    permissionIntent,
                    flags
                )
                manager.requestPermission(device, intent)
                listener("MFI USB device found without permission; requested permission")
                return null
            }
            return UsbMfiAuthProvider(context.applicationContext, manager, device, File(context.filesDir, "cert.bin"), cachedCertificate, listener)
                .takeIf { it.certificate != null }
                ?: run {
                    listener("MFI USB device found but certificate read failed")
                    null
                }
        }

        private fun logVisibleUsbDevices(manager: UsbManager, listener: (String) -> Unit) {
            val devices = manager.deviceList.values.toList()
            if (devices.isEmpty()) {
                listener("USB device list is empty; local MFI VID=0x1a86 PID=0x5512 is not visible")
                return
            }
            devices.forEach { device ->
                listener(
                    "USB visible name=${device.deviceName} " +
                        "vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} " +
                        "class=${device.deviceClass} interfaces=${device.interfaceCount} " +
                        "hasPermission=${manager.hasPermission(device)}"
                )
            }
        }
    }
}

