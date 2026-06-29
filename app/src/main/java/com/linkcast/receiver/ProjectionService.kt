package com.linkcast.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.widget.Toast
import com.example.autoservice.carplay.CarplayNative
import com.linkcast.receiver.diag.LinkLog
import com.linkcast.receiver.auth.LocalMfiAuthProvider
import com.linkcast.receiver.auth.NetworkMfiAuthProvider
import com.linkcast.receiver.media.AudioPipeline
import com.linkcast.receiver.media.VideoPipeline
import com.linkcast.receiver.nativebridge.NativeCallbackRegistry
import com.linkcast.receiver.nativebridge.NativeCallbacks
import com.linkcast.receiver.net.AirPlayAdvertiser
import com.linkcast.receiver.net.HotspotProvider
import com.linkcast.receiver.net.MdnsDiscovery
import com.linkcast.receiver.transport.BtIap2Transport
import com.linkcast.receiver.ui.MainActivity
import java.nio.ByteBuffer

class ProjectionService : Service(), NativeCallbacks, BtIap2Transport.Listener {
    /** UI status bus. The Activity registers to render the phase + a log tail. */
    interface StatusListener {
        fun onPhase(phase: String, connecting: Boolean)
        fun onLog(line: String)
    }

    companion object {
        private const val TAG = "ProjectionService"
        private const val CHANNEL_ID = "linkcast_projection"
        private const val NOTIFICATION_ID = 1
        private const val CONNECTING_TIMEOUT_MS = 120_000L
        private const val AUTO_CONNECT_DELAY_MS = 500L
        private const val ACTION_CONNECT = "com.linkcast.receiver.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.linkcast.receiver.action.DISCONNECT"
        // Bundled archive (classes.dex + resources.arsc paired with the native libs)
        // used for the native integrity check; staged from assets to filesDir.
        private const val VERIFICATION_RES_FILE = "receiver_res.zip"

        @Volatile private var instance: ProjectionService? = null

        @Volatile var statusListener: StatusListener? = null

        /** Latest phase text so a freshly-bound Activity can render immediately. */
        @Volatile var lastPhase: String = "空闲"
            private set
        @Volatile var isConnecting: Boolean = false
            private set

        fun cancel(context: Context) = disconnect(context)

        fun start(context: Context) {
            val intent = Intent(context, ProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun connect(context: Context) {
            val intent = Intent(context, ProjectionService::class.java).setAction(ACTION_CONNECT)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_DISCONNECT))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProjectionService::class.java))
        }

        // The SurfaceView's surface is often created BEFORE the service instance exists
        // (and surfaceCreated won't fire again), so cache it statically and (re)apply it
        // both here and when the service comes up — otherwise frames arrive with a null
        // surface and are dropped (black screen).
        @Volatile private var pendingSurface: Surface? = null
        @Volatile private var pendingW: Int = 0
        @Volatile private var pendingH: Int = 0

        fun attachSurface(surface: Surface?, width: Int, height: Int) {
            pendingSurface = surface
            pendingW = width
            pendingH = height
            instance?.videoPipeline?.setSurface(surface, width, height)
        }
    }

    private lateinit var config: LinkConfig
    private lateinit var hotspotProvider: HotspotProvider
    private lateinit var mdnsDiscovery: MdnsDiscovery
    private lateinit var airPlayAdvertiser: AirPlayAdvertiser
    private lateinit var transport: BtIap2Transport
    private lateinit var stateMachine: ProjectionStateMachine
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private val videoPipeline = VideoPipeline()
    private val audioPipeline = AudioPipeline()
    private var authProvider: AuthProvider = EmptyAuthProvider

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 切回屏上 Surface 后(如从主页返回),请求发送端补发关键帧以恢复画面。
        // 参数取 1 才真正请求关键帧(原生侧:0/2 是 UI 外观更新,1 才是 ForceKeyFrame)。
        videoPipeline.onDecoderReady = {
            log("Surface 就绪,请求关键帧 forceKeyFrame(1)")
            runCatching { CarplayNative.forceKeyFrame(1) }
                .onFailure { log("forceKeyFrame 失败: $it") }
        }
        config = LinkConfig(this)
        LinkLog.enabled = config.diagLogEnabled
        workerThread = HandlerThread("linkcast-service").also { it.start() }
        worker = Handler(workerThread.looper)
        hotspotProvider = HotspotProvider(this, config, { showHotspotWarning(it) }) {
            getString(R.string.app_name)
        }
        mdnsDiscovery = MdnsDiscovery(this, "axb789") { log(it) }
        airPlayAdvertiser = AirPlayAdvertiser(this) { log(it) }
        authProvider = LocalMfiAuthProvider.open(this) { log(it) }
        transport = newTransport()
        stateMachine = ProjectionStateMachine(::onProjectionStateChanged) { cleanupForReconnect() }
        NativeCallbackRegistry.attach(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        prepareNativePaths()
        // Apply any surface that the Activity created before this service existed.
        pendingSurface?.let { videoPipeline.setSurface(it, pendingW, pendingH) }
        // Manual connect only: the user starts a connection from the UI. (Auto-connect
        // on launch removed for easier test control.) The SoftAP is also NOT started
        // here — it would drop the Wi-Fi client / internet the network MFi signer needs.
        publishPhase("空闲", false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> worker.post { startConnectionSequence() }
            ACTION_DISCONNECT -> worker.post { cancelConnection() }
        }
        return START_STICKY
    }

    private fun newTransport() =
        BtIap2Transport(this, { config.defaultBluetoothAddress }, this)

    override fun onDestroy() {
        NativeCallbackRegistry.detach(this)
        cleanupForStop()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun challengeResponse(length: Int, challenge: ByteArray, response: ByteArray): Int {
        val result = authProvider.respond(challenge, length) ?: return 0
        val copyLength = result.size.coerceAtMost(response.size)
        result.copyInto(response, endIndex = copyLength)
        return copyLength
    }

    override fun onConnectionStatus(kind: Int, status: Int) {
        worker.post {
            log("Native status kind=$kind status=${CarplayNative.statusString(status)}")
            if (kind == 1) {
                stateMachine.acceptNativeStatus(status)
            }
        }
    }

    override fun onVideoData(flags: Int, buffer: ByteBuffer) {
        videoPipeline.queueFrame(flags, buffer)
    }

    override fun onAudioStreamCreate(direction: Int, key: String, format: String) {
        worker.post { audioPipeline.create(direction, key, format) }
    }

    override fun onAudioStreamDestroy(key: String) {
        worker.post { audioPipeline.destroy(key) }
    }

    override fun outputData(channel: Int, bytes: ByteArray) {
        transport.writeFromNative(bytes)
    }

    override fun onMediaInfoUpdate(type: Int, metadata: Array<String>) {
        log("Media update type=$type ${metadata.joinToString()}")
    }

    override fun onTelephonyUpdate(type: Int, info: Array<String>) {
        log("Telephony update type=$type ${info.joinToString()}")
    }

    override fun onRouteGuidanceUpdate(type: Int, data: ByteArray) = Unit
    override fun onRouteGuidanceManeuverInformation(type: Int, data: ByteArray) = Unit
    override fun onLaneGuidanceInformation(type: Int, data: ByteArray) = Unit
    override fun outputDebugString(tag: String, message: String) = log("$tag: $message")

    override fun onTransportLog(message: String) = log(message)

    override fun onTransportDisconnected() {
        worker.post {
            stateMachine.reset()
        }
    }

    private fun startAutoConnectIfEnabled() {
        if (config.autoConnect) {
            log("Auto connect scheduled after $AUTO_CONNECT_DELAY_MS ms")
            startConnectionSequence()
        } else {
            log("Auto connect disabled by key_auto_connect")
        }
    }

    @Volatile private var networkProviderTried = false

    private fun ensureAuthProvider() {
        // USB chip (local signer) is chosen in onCreate. When none is present, fall
        // back to the network MFi service (must run off the main thread — we're on the
        // service worker here). Done before start() so the cert it returns is ready.
        if (authProvider.canSign) return
        if (networkProviderTried || !config.networkMfiEnabled) return
        networkProviderTried = true
        val net = runCatching { NetworkMfiAuthProvider.open(this, config) { log(it) } }
            .onFailure { log("Network MFI open failed: $it") }
            .getOrNull() ?: return
        authProvider.release()
        authProvider = net
        log("Network MFI provider ready; cert=${net.certificate.size}")
    }

    private fun startConnectionSequence() {
        if (isConnecting) {
            log("Connect ignored: already connecting")
            return
        }
        // A previous attempt shuts the transport's executor down; use a fresh one so
        // re-connect after a cancel runs the full flow again.
        transport = newTransport()
        // Drop any stale network-MFi connection so a FRESH signing socket is opened for
        // this attempt. Reusing one from a minutes-old attempt yields "Broken pipe" at
        // the challenge. (A USB signer is persistent — leave it alone.)
        if (authProvider is NetworkMfiAuthProvider) {
            runCatching { authProvider.release() }
            authProvider = EmptyAuthProvider
            networkProviderTried = false
        }
        publishPhase("连接中(认证)…", true)
        ensureAuthProvider()
        // 在手机请求 Wi-Fi 配置之前先把热点起好并发布真实凭据,否则手机会拿到占位凭据连接失败。
        hotspotProvider.ensureStarted()
        // 原生引擎(setWifiConfiguration + setResolutions + start)在主 looper 线程初始化并阻塞至完成,
        // 之后蓝牙线程才创建 iAP2 链路并喂 income_data;线程亲和性不对会导致原生会话崩溃。
        ensureNativeEngineStarted()
        transport.startAutoConnect()
    }

    override fun onLinkReady() {
        // Engine is already started on the main thread before the link is created.
    }

    private fun ensureNativeEngineStarted() {
        if (CarplayNative.isNativeStarted()) return
        val certificate = loadMfiCertificate()
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            val resolution = config.selectedResolution()
            ProjectionMetrics.update(resolution.x, resolution.y)
            videoPipeline.setVideoSize(resolution.x, resolution.y)
            val payload = config.resolutionPayload()
            CarplayNative.configureResolutions(payload.resolutions, payload.count, payload.options)
            // 启动前写入占位热点凭据,真实凭据在热点就绪后由 HotspotProvider 覆盖。
            hotspotProvider.publishPlaceholder()
            // Offline the native layer self-provides its built-in MFi identity and
            // self-signs; supplying an externally-sourced certificate here pairs a
            // cert with the wrong signing key and the device rejects auth. Only pass
            // a certificate when a real external signer (USB chip) actually backs it.
            val cfg = authProvider.certificate ?: certificate
            val started = CarplayNative.startNativeSession(cfg, cfg.size, 1, config.audioBuffers)
            log("Native AV engine ${if (started) "started" else "already running"} on main thread; cfg=${cfg.size}")
            latch.countDown()
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun loadMfiCertificate(): ByteArray =
        runCatching { assets.open("mfi_cert.bin").use { it.readBytes() } }
            .getOrElse {
                log("mfi_cert.bin missing: $it")
                ByteArray(0)
            }

    private fun prepareNativePaths() {
        mainHandler.post {
            // The native engine integrity-checks the archive passed as the third path
            // argument (it inspects fixed entries — classes.dex/resources.arsc — whose
            // CRC must match the ones the reused native libs were built against). We
            // bundle exactly those entries in assets and stage them to filesDir, so the
            // app is self-contained (no dependency on the companion app being installed).
            val verificationArchive = stagedVerificationArchive()
            CarplayNative.setDataPath(filesDir.absolutePath, "My Car", verificationArchive)
            val payload = config.resolutionPayload()
            CarplayNative.configureResolutions(payload.resolutions, payload.count, payload.options)
            log("Native paths prepared; archive=$verificationArchive resolution=${payload.resolutions.joinToString()} opts=${payload.options.joinToString()}")
        }
    }

    /** Copy the bundled verification archive from assets to filesDir once; return its path. */
    private fun stagedVerificationArchive(): String {
        val out = java.io.File(filesDir, VERIFICATION_RES_FILE)
        if (!out.isFile || out.length() == 0L) {
            runCatching {
                assets.open(VERIFICATION_RES_FILE).use { input ->
                    java.io.FileOutputStream(out).use { input.copyTo(it) }
                }
            }.onFailure { log("Failed to stage $VERIFICATION_RES_FILE: $it") }
        }
        return out.absolutePath
    }

    private fun onProjectionStateChanged(state: ProjectionState) {
        // Surface every stage to the UI. Still "connecting" until video starts or it fails.
        val connecting = when (state) {
            ProjectionState.VideoStream, ProjectionState.ConnectFailed,
            ProjectionState.AuthFailed, ProjectionState.Idle -> false
            else -> true
        }
        publishPhase(phaseTextFor(state), connecting)
        when (state) {
            ProjectionState.AuthSucceeded -> {
                hotspotProvider.ensureStarted()
                // Advertise our AirPlay receiver (_airplay._tcp, port 7000) so the phone
                // can discover the video endpoint and open the reverse stream after the
                // control link — without this the phone connects control but never video.
                airPlayAdvertiser.start()
                // Once the phone joins the AP it advertises _carplay-ctrl._tcp; discover
                // it and hand its endpoint to the native to open the video session.
                mdnsDiscovery.start()
            }
            ProjectionState.Connecting -> worker.postDelayed({ handleConnectingTimeout() }, CONNECTING_TIMEOUT_MS)
            ProjectionState.Connected, ProjectionState.VideoStream -> {
                // The native plays the AirPlay audio itself via AAudio; on automotive ROMs
                // that output is only routed to the speaker once the app holds media audio
                // focus (the original requests it on each audio stream). Request it here.
                requestMediaAudioFocus()
                if (state == ProjectionState.VideoStream) log("Milestone 4 reached: video stream active")
            }
            else -> Unit
        }
    }

    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private fun requestMediaAudioFocus() {
        if (audioFocusRequest != null) return
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        val result = am.requestAudioFocus(req)
        audioFocusRequest = req
        log("requestAudioFocus(media) -> $result")
    }

    private fun abandonAudioFocus() {
        val req = audioFocusRequest ?: return
        runCatching { getSystemService(android.media.AudioManager::class.java)?.abandonAudioFocusRequest(req) }
        audioFocusRequest = null
    }

    private fun cancelConnection() {
        log("Connection cancelled by user")
        transport.stop()
        mdnsDiscovery.stop()
        airPlayAdvertiser.stop()
        abandonAudioFocus()
        hotspotProvider.stop()
        stateMachine.reset()
        // Fresh transport so the next Start runs the whole flow cleanly.
        transport = newTransport()
        publishPhase("已取消", false)
    }

    private fun handleConnectingTimeout() {
        if (stateMachine.state == ProjectionState.Connecting) {
            log("Connecting timed out after $CONNECTING_TIMEOUT_MS ms")
            cleanupForReconnect()
        }
    }

    private fun cleanupForReconnect() {
        transport.stop()
        mdnsDiscovery.stop()
        airPlayAdvertiser.stop()
        abandonAudioFocus()
        hotspotProvider.stop()
        stateMachine.reset()
        transport = newTransport()
        publishPhase("已断开,可重新连接", false)
    }

    private fun cleanupForStop() {
        transport.stop()
        mdnsDiscovery.stop()
        airPlayAdvertiser.stop()
        abandonAudioFocus()
        hotspotProvider.stop()
        audioPipeline.release()
        videoPipeline.release()
        authProvider.release()
        authProvider = EmptyAuthProvider
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        runCatching { CarplayNative.stop() }
        CarplayNative.markNativeStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val l = statusListener ?: return
        mainHandler.post { runCatching { l.onLog(message) } }
    }

    // 热点并发冲突等非致命提示:记录日志并弹 Toast,不打断本次连接。
    private fun showHotspotWarning(message: String) {
        log(message)
        mainHandler.post { runCatching { Toast.makeText(this, message, Toast.LENGTH_LONG).show() } }
    }

    private fun publishPhase(phase: String, connecting: Boolean) {
        lastPhase = phase
        isConnecting = connecting
        val l = statusListener ?: return
        mainHandler.post { runCatching { l.onPhase(phase, connecting) } }
    }

    private fun phaseTextFor(state: ProjectionState): String = when (state) {
        ProjectionState.Idle -> "空闲"
        ProjectionState.Authing -> "认证中…"
        ProjectionState.AuthSucceeded -> "认证成功,准备 WiFi 热点"
        ProjectionState.Connecting -> "连接中(等待 iPhone 加入热点)…"
        ProjectionState.Connected -> "已连接"
        ProjectionState.VideoStream -> "投屏中"
        ProjectionState.ConnectFailed -> "连接失败"
        ProjectionState.AuthFailed -> "认证失败"
    }
}
