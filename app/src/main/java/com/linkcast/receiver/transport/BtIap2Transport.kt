package com.linkcast.receiver.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.autoservice.carplay.CarplayNative
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BtIap2Transport(
    private val context: Context,
    private val preferredDeviceAddress: () -> String,
    private val listener: Listener,
) {
    interface Listener {
        fun onTransportLog(message: String)
        fun onTransportDisconnected()
        // Called right after the iAP2 link is created but BEFORE any bytes are fed.
        // The native AV engine must be started here so it can claim the freshly
        // created link (wires Iap2Link's transport member); starting the engine
        // before the link exists leaves that member dangling and crashes sendLsp.
        fun onLinkReady()
        // RFCOMM 已连上、握手已发出(进入"等 iPhone 回 iAP2/认证"阶段)。用于起连接阶段看门狗:
        // 手机已接上才算连接开始,即便 native 后续不上报任何状态(手机端没回握手)也能被超时兜住。
        fun onRfcommConnected()
    }

    companion object {
        val IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        private const val TAG = "BtIap2Transport"
        private const val READ_BUFFER_SIZE = 512
        private const val MAX_WRITE_FAILURES = 3
        private const val MAX_READ_FAILURE_LOGS = 3
        private val INITIAL_HANDSHAKE = byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10)
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "linkcast-rfcomm")
    }
    private val stopping = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val writeFailures = AtomicInteger(0)

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var linkHandle: Long = 0L

    fun startAutoConnect() {
        if (!started.compareAndSet(false, true)) {
            listener.onTransportLog("Bluetooth auto-connect is already running")
            return
        }
        if (!hasBluetoothConnectPermission()) {
            listener.onTransportLog("BLUETOOTH_CONNECT permission is missing")
            started.set(false)
            return
        }
        executor.execute { connectLoop() }
    }

    fun stop() {
        stopping.set(true)
        closeSocket()
        val handle = linkHandle
        if (handle != 0L) {
            runCatching { CarplayNative.destroyIap2Link(handle) }
            linkHandle = 0L
        }
        executor.shutdownNow()
    }

    fun writeFromNative(bytes: ByteArray) {
        val stream = output ?: return
        try {
            stream.write(bytes)
            stream.flush()
            writeFailures.set(0)
        } catch (error: Exception) {
            val failures = writeFailures.incrementAndGet()
            listener.onTransportLog("Bluetooth output_data write failed $failures/$MAX_WRITE_FAILURES: $error")
            if (failures >= MAX_WRITE_FAILURES) {
                closeSocket()
                listener.onTransportDisconnected()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectLoop() {
        while (!stopping.get()) {
            SystemClock.sleep(300L)
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val bondedDevices = adapter?.bondedDevices.orEmpty().filter { it.bondState == BluetoothDevice.BOND_BONDED }
            val preferredAddress = preferredDeviceAddress().trim()
            val device = bondedDevices.firstOrNull { it.address.equals(preferredAddress, ignoreCase = true) }
                ?: bondedDevices.firstOrNull()
            if (device == null) {
                listener.onTransportLog("No bonded Bluetooth device found; retry in 1000 ms")
                SystemClock.sleep(1000L)
                continue
            }
            listener.onTransportLog("Connecting RFCOMM to ${device.name ?: device.address}")
            try {
                adapter.cancelDiscovery()
                // 先创建 iAP2 链路、启动原生引擎,再连接 RFCOMM。
                linkHandle = CarplayNative.createIap2LinkHandle()
                listener.onLinkReady()
                device.createRfcommSocketToServiceRecord(IAP2_UUID).use { btSocket ->
                    socket = btSocket
                    btSocket.connect()
                    output = btSocket.outputStream
                    // Register this link as the ACTIVE session BEFORE any bytes flow.
                    // The native send path reads per-session state (current link +
                    // host class ref) that is populated only by this call; without it
                    // the first inbound iAP2 sync triggers a send against uninitialised
                    // state and crashes. Must run after the output stream is ready so
                    // any sync the native emits here can be written.
                    CarplayNative.CarPlayStartSession(linkHandle)
                    output?.write(INITIAL_HANDSHAKE)
                    writeFailures.set(0)
                    listener.onTransportLog("RFCOMM connected; iAP2 handle=$linkHandle")
                    listener.onRfcommConnected()
                    readLoop(btSocket.inputStream)
                }
            } catch (error: Exception) {
                if (!stopping.get()) {
                    listener.onTransportLog("RFCOMM connect/read failed: $error; retry in 1000 ms")
                    listener.onTransportDisconnected()
                    SystemClock.sleep(1000L)
                }
            } finally {
                output = null
                closeSocket()
                val handle = linkHandle
                if (handle != 0L) {
                    runCatching { CarplayNative.destroyIap2Link(handle) }
                    linkHandle = 0L
                }
            }
        }
    }

    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var readFailures = 0
        while (!stopping.get()) {
            try {
                val read = input.read(buffer)
                if (read < 0) break
                // Feed the iAP2 link UNCONDITIONALLY. Authentication (iAP2 + MFi)
                // runs inside the native link and happens BEFORE the AV engine is
                // started, so gating on isNativeStarted() would drop the auth bytes.
                if (read > 0 && linkHandle != 0L) {
                    CarplayNative.income_data(linkHandle, buffer, read)
                }
            } catch (error: java.io.IOException) {
                if (readFailures < MAX_READ_FAILURE_LOGS) {
                    listener.onTransportLog("Bluetooth input read failed ${readFailures + 1}/$MAX_READ_FAILURE_LOGS: $error")
                } else {
                    break
                }
                readFailures += 1
                SystemClock.sleep(200L)
            }
        }
        listener.onTransportDisconnected()
    }

    private fun closeSocket() {
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
