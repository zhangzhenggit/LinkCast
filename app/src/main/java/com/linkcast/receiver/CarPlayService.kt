package com.linkcast.receiver

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.autoservice.carplay.CarplayNative
import com.linkcast.receiver.diag.FileLogSink
import com.linkcast.receiver.diag.LinkLog
import com.linkcast.receiver.auth.LocalMfiAuthProvider
import com.linkcast.receiver.auth.NetworkMfiAuthProvider
import com.linkcast.receiver.auth.NetworkMfiRejected
import com.linkcast.receiver.media.AudioPipeline
import com.linkcast.receiver.media.CodecSupport
import com.linkcast.receiver.media.VideoPipeline
import com.linkcast.receiver.nativebridge.NativeCallbackRegistry
import com.linkcast.receiver.nativebridge.NativeCallbacks
import com.linkcast.receiver.net.AirPlayAdvertiser
import com.linkcast.receiver.net.BluetoothPresenceMonitor
import com.linkcast.receiver.net.HotspotProvider
import com.linkcast.receiver.net.MdnsDiscovery
import com.linkcast.receiver.net.NetworkMonitor
import com.linkcast.receiver.transport.BtIap2Transport
import com.linkcast.receiver.ui.LinkActivity
import java.nio.ByteBuffer

class CarPlayService : Service(), NativeCallbacks, BtIap2Transport.Listener {
    /** UI status bus. The Activity registers to render status + a detail line + a log tail. */
    interface StatusListener {
        // 主状态(headline):UI 据此显示文案/颜色与按钮态。
        fun onStatus(status: ConnectionStatus)
        // 细节子行:native 进度文案(如"等待 iPhone 加入热点…")。
        fun onPhase(detail: String)
        fun onLog(line: String)
    }

    companion object {
        private const val TAG = "CarPlayService"
        private const val CHANNEL_ID = "linkcast_projection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CONNECT = "com.linkcast.receiver.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.linkcast.receiver.action.DISCONNECT"
        private const val ACTION_AUTO = "com.linkcast.receiver.action.AUTO"
        private const val EXTRA_AUTO = "auto_enabled"
        // Bundled archive (classes.dex + resources.arsc paired with the native libs)
        // used for the native integrity check; staged from assets to filesDir.
        private const val VERIFICATION_RES_FILE = "receiver_res.zip"

        @Volatile private var instance: CarPlayService? = null

        @Volatile var statusListener: StatusListener? = null

        /** 最近的 native 进度细节文案,供新绑定的 Activity 立即渲染。 */
        @Volatile var lastPhase: String = ""
            private set

        /** 对外连接状态(headline),供界面展示与快照渲染。 */
        @Volatile var connectionStatus: ConnectionStatus = ConnectionStatus.Off
            private set

        fun cancel(context: Context) = disconnect(context)

        fun start(context: Context) {
            val intent = Intent(context, CarPlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun connect(context: Context) {
            val intent = Intent(context, CarPlayService::class.java).setAction(ACTION_CONNECT)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, CarPlayService::class.java).setAction(ACTION_DISCONNECT))
        }

        /** 自动连接总开关。开启时会拉起服务并进入循环连接。 */
        fun setAutoConnect(context: Context, enabled: Boolean) {
            val intent = Intent(context, CarPlayService::class.java)
                .setAction(ACTION_AUTO)
                .putExtra(EXTRA_AUTO, enabled)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarPlayService::class.java))
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
    private lateinit var controller: ConnectionController
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var bluetoothMonitor: BluetoothPresenceMonitor
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // 签名在独立线程跑(可能排队等待签名服务器),避免阻塞 worker 上的状态机。
    private lateinit var signingExecutor: java.util.concurrent.ExecutorService
    // 代次令牌:每次启动/停止递增,使在途签名完成后能判断本次尝试是否已被取消/重起。仅 worker 访问。
    private var signGeneration = 0
    private var currentCanceller: NetworkMfiAuthProvider.Canceller? = null

    private val videoPipeline = VideoPipeline()
    private val audioPipeline = AudioPipeline()
    private var authProvider: AuthProvider = EmptyAuthProvider
    private var fileLogSink: FileLogSink? = null

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
        // HEVC 解码致命失败 → 持久化标记 + 提示 + 重连改用 H.264。
        videoPipeline.onDecoderFatal = { wasHevc -> worker.post { handleDecoderFatal(wasHevc) } }
        config = LinkConfig(this)
        LinkLog.enabled = config.diagLogEnabled
        // 文件日志:本机 ROM 屏蔽 logcat,落地到私有目录便于取证(后续接 App 内日志页)。
        fileLogSink = FileLogSink(java.io.File(filesDir, "linkcast-log.txt")).also { LinkLog.addSink(it) }
        workerThread = HandlerThread("linkcast-service").also { it.start() }
        worker = Handler(workerThread.looper)
        signingExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "linkcast-signing")
        }
        hotspotProvider = HotspotProvider(this, config, { showHotspotWarning(it) }) {
            getString(R.string.app_name)
        }
        mdnsDiscovery = MdnsDiscovery(this, "axb789") { log(it) }
        airPlayAdvertiser = AirPlayAdvertiser(this) { log(it) }
        authProvider = LocalMfiAuthProvider.open(this) { log(it) }
        transport = newTransport()
        // 失败由 ConnectionController 经 native 状态统一决策,状态机的失败回调不再单独拆会话。
        stateMachine = ProjectionStateMachine(::onProjectionStateChanged) {}
        // 前置条件(权限/蓝牙/网络)以 lambda 注入,使控制器与 Android 细节解耦。
        controller = ConnectionController(
            worker, ::startSession, ::stopSession, ::onConnectionStatusChanged,
            ::hasBluetoothConnectPermission, ::bluetoothReady, ::networkReady,
        )
        // 事件源:网络恢复 / 蓝牙开关 → 驱动控制器(均切到 worker 线程,与会话操作同线程)。
        networkMonitor = NetworkMonitor(this) { worker.post { controller.onNetworkAvailable() } }
        bluetoothMonitor = BluetoothPresenceMonitor(
            this,
            { worker.post { controller.onBluetoothAvailable() } },
            { worker.post { controller.onBluetoothUnavailable() } },
        )
        networkMonitor.start()
        bluetoothMonitor.start()
        NativeCallbackRegistry.attach(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        prepareNativePaths()
        // Apply any surface that the Activity created before this service existed.
        pendingSurface?.let { videoPipeline.setSurface(it, pendingW, pendingH) }
        // 按持久化的总开关决定是否进入循环连接(关=空闲,开=开始循环)。
        // (开机自启/网络触发等启动时机后续单独处理。)
        worker.post { controller.setAutoEnabled(config.autoConnectEnabled) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> worker.post { controller.onManualConnect() }
            ACTION_DISCONNECT -> worker.post { controller.onUserDisconnect() }
            ACTION_AUTO -> {
                val enabled = intent.getBooleanExtra(EXTRA_AUTO, false)
                config.autoConnectEnabled = enabled
                worker.post { controller.setAutoEnabled(enabled) }
            }
        }
        return START_STICKY
    }

    private fun newTransport() =
        BtIap2Transport(this, { config.defaultBluetoothAddress }, this)

    // —— 控制器前置条件(供 ConnectionController 预检)——

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // 蓝牙是否整体可用:适配器开 且 有已配对设备(可主动发起 RFCOMM 的前提)。
    private fun bluetoothReady(): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        return runCatching { adapter.bondedDevices?.isNotEmpty() == true }.getOrDefault(false)
    }

    // 是否具备签名所需网络:用本地签名(USB)时不需要;否则要有可上网的活动网络。
    private fun networkReady(): Boolean {
        if (!config.networkMfiEnabled) return true
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

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
                val state = ProjectionState.fromStatus(status)
                stateMachine.acceptNativeStatus(status)
                feedController(state)
            }
        }
    }

    // 把 native 上报的真实状态喂给控制器(状态机 reset 产生的 Idle 不走这里,避免误判为掉线)。
    // 终态"认证被拒"由签名阶段的服务器 0x62 权威给出;native 侧 AuthFailed 一律当可重试的瞬时失败,
    // 避免断网/RFCOMM 闪断导致的重认证失败被误锁成终态。
    private fun feedController(state: ProjectionState) {
        controller.onProjectionState(state)
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

    override fun onRfcommConnected() {
        // RFCOMM 连上即视为进入连接阶段(起看门狗),覆盖"已连上但 native 不上报"的卡死。
        worker.post { controller.onLinkEngaged() }
    }

    override fun onTransportDisconnected() {
        worker.post {
            stateMachine.reset()
            // 链路断开(含投屏中手机走远)→ 作为可重试失败喂给控制器:状态回到"等待连接",
            // transport 自身继续重试 RFCOMM,走近即自动重连。会话已被拆除时(sessionActive=false)控制器会忽略。
            controller.onProjectionState(ProjectionState.ConnectFailed)
        }
    }

    // 签名结果(在 signingExecutor 线程算出,回到 worker 落定)。
    private sealed interface SignOutcome {
        object Proceed : SignOutcome                               // 无需网络签名 / 暂不可用 → 继续(native 自签)
        object Rejected : SignOutcome                              // 服务端终态裁决(0x62)→ 暂停"认证被拒"
        data class Ready(val provider: NetworkMfiAuthProvider) : SignOutcome
    }

    // 发起一次连接尝试(由 ConnectionController 调度,调用前会话已停)。
    // 签名可能要等签名服务器排队,放到独立线程跑;期间 worker 上的状态机保持响应。
    private fun startSession() {
        // 上一次尝试会关闭 transport 的 executor,这里换一个新的,保证重连能跑完整流程。
        transport = newTransport()
        // Drop any stale network-MFi connection so a FRESH signing socket is opened for
        // this attempt. Reusing one from a minutes-old attempt yields "Broken pipe" at
        // the challenge. (A USB signer is persistent — leave it alone.)
        if (authProvider is NetworkMfiAuthProvider) {
            runCatching { authProvider.release() }
            authProvider = EmptyAuthProvider
        }
        publishDetail("连接中(认证)…")
        // 取消上一次可能仍在途的签名,开新一代;到时凭代次判断本次尝试是否已被取消/重起。
        currentCanceller?.cancel()
        val gen = ++signGeneration
        val canceller = NetworkMfiAuthProvider.Canceller()
        currentCanceller = canceller
        signingExecutor.execute {
            val outcome = computeSigning(canceller)
            worker.post { applySigningOutcome(gen, outcome) }
        }
    }

    // 在 signingExecutor 线程阻塞计算签名结果(可能等待签名服务器排队)。
    private fun computeSigning(canceller: NetworkMfiAuthProvider.Canceller): SignOutcome {
        if (authProvider.canSign) return SignOutcome.Proceed       // 已有本地签名器(USB),无需网络
        if (!config.networkMfiEnabled) return SignOutcome.Proceed
        val result = runCatching { NetworkMfiAuthProvider.open(this, config, canceller) { log(it) } }
        val error = result.exceptionOrNull()
        if (error is NetworkMfiRejected) {
            log("Network MFI 认证被拒: ${error.message}")
            return SignOutcome.Rejected
        }
        if (error != null) {
            log("Network MFI open failed: $error")
            return SignOutcome.Proceed   // 暂时不可用(连不上/排队超时/被取消):离线时 native 可自签,继续。
        }
        return result.getOrNull()?.let { SignOutcome.Ready(it) } ?: SignOutcome.Proceed
    }

    // 回到 worker 落定签名结果:代次过期(已取消/已重起)则丢弃;被拒则暂停;否则续起会话。
    private fun applySigningOutcome(gen: Int, outcome: SignOutcome) {
        if (gen != signGeneration) {
            (outcome as? SignOutcome.Ready)?.provider?.let { runCatching { it.release() } }
            return
        }
        when (outcome) {
            is SignOutcome.Ready -> {
                authProvider.release()
                authProvider = outcome.provider
                log("Network MFI provider ready; cert=${outcome.provider.certificate.size}")
                continueStartSession()
            }
            SignOutcome.Rejected -> controller.onAuthRejected()
            SignOutcome.Proceed -> continueStartSession()
        }
    }

    // 签名就绪后续起会话:起热点、原生引擎、RFCOMM。均在既有 worker/main 线程上,保持原顺序。
    private fun continueStartSession() {
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
            // 码型决策(协商与解码器共用):有 HEVC 解码器且未崩过 → HEVC,否则 H.264。
            val codecHevc = config.effectiveCodecType() == LinkConfig.CODEC_HEVC
            videoPipeline.setCodec(codecHevc)
            log("码型决策=${if (codecHevc) "HEVC" else "H.264"} 本机HEVC解码器=${CodecSupport.hevcDecoderAvailable()}")
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
        // 把每个 native 阶段作为细节子行展示;主状态由 ConnectionController 统一给出。
        publishDetail(phaseTextFor(state))
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

    // 停止当前会话(含原生引擎),由 ConnectionController 调度。停 native 让下次 startSession
    // 按当前决策重新协商并重建热点/视频会话。stateMachine.reset 不喂控制器,避免误判掉线。
    private fun stopSession() {
        // 取消在途签名并作废其续起:close 让阻塞的 open() 立即退出,代次递增使其回调被丢弃。
        currentCanceller?.cancel()
        currentCanceller = null
        signGeneration++
        transport.stop()
        mdnsDiscovery.stop()
        airPlayAdvertiser.stop()
        abandonAudioFocus()
        hotspotProvider.stop()
        stateMachine.reset()
        runCatching { CarplayNative.stop() }
        CarplayNative.markNativeStopped()
        // 清掉屏上定格的最后一帧(解码器随会话停掉,否则画面会一直留着)。
        videoPipeline.clear()
        transport = newTransport()
    }

    private fun cleanupForStop() {
        controller.onStopped()
        currentCanceller?.cancel()
        currentCanceller = null
        runCatching { signingExecutor.shutdownNow() }
        runCatching { networkMonitor.stop() }
        runCatching { bluetoothMonitor.stop() }
        transport.stop()
        mdnsDiscovery.stop()
        airPlayAdvertiser.stop()
        abandonAudioFocus()
        hotspotProvider.release()
        audioPipeline.release()
        videoPipeline.release()
        authProvider.release()
        authProvider = EmptyAuthProvider
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        runCatching { CarplayNative.stop() }
        CarplayNative.markNativeStopped()
        fileLogSink?.let { LinkLog.removeSink(it) }
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
            Intent(this, LinkActivity::class.java),
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
        // 经 LinkLog 走(logcat + 文件 sink),被 ROM 屏蔽 logcat 时仍可从文件取证。
        LinkLog.i(TAG) { message }
        val l = statusListener ?: return
        mainHandler.post { runCatching { l.onLog(message) } }
    }

    // 热点并发冲突等非致命提示:记录日志并弹 Toast,不打断本次连接。
    private fun showHotspotWarning(message: String) = toast(message)

    private fun toast(message: String) {
        log(message)
        mainHandler.post { runCatching { Toast.makeText(this, message, Toast.LENGTH_LONG).show() } }
    }

    // HEVC 解码致命失败时回退到 H.264:持久化标记、提示,并按失败触发一次重连(下次协商即用 H.264)。
    private fun handleDecoderFatal(wasHevc: Boolean) {
        // 只处理 HEVC 失败;若已回退则忽略后续重复回调。
        if (!wasHevc || config.effectiveCodecType() != LinkConfig.CODEC_HEVC) return
        config.markHevcFailed()
        toast("HEVC 解码失败,已切换 H.264")
        // 重建会话:下次协商即用 H.264(仅相关标记已持久化,restart 会按新决策重起原生引擎)。
        controller.restartSession()
    }

    // 连接状态变化(来自 ConnectionController):记录并暴露给界面。
    private fun onConnectionStatusChanged(status: ConnectionStatus) {
        connectionStatus = status
        log("连接状态: ${status.label}")
        val l = statusListener ?: return
        mainHandler.post { runCatching { l.onStatus(status) } }
    }

    // native 进度细节子行(主状态由 onConnectionStatusChanged 给出)。
    private fun publishDetail(detail: String) {
        lastPhase = detail
        val l = statusListener ?: return
        mainHandler.post { runCatching { l.onPhase(detail) } }
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
