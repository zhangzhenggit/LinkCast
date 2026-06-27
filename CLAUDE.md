# LinkCast — 纯软件无线 CarPlay 接收端

LinkCast 是一个 Android 投屏接收应用,在平板/车机上作为 **无线 CarPlay** 接收端:
iPhone 通过蓝牙 + WiFi 把屏幕、触控、音频投到本机。已实测**画面、触屏、声音全部可用**。

实现方式:**复用某商业投屏 App 的原生库**(`libcarplay2_jni.so` / `libcarplay_jni.so`),
用全新的 Kotlin/Java 胶水层(`com.linkcast.receiver.*`)驱动它完成完整 CarPlay 流程。

## 构建 / 安装

```
gradlew.bat :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

- `applicationId = com.linkcast.receiver`,minSdk 26 / target 34,ABI `arm64-v8a` + `armeabi-v7a`。
- **debug 构建必须 `isDebuggable=false`**(见 `app/build.gradle.kts`):原生库的 JNI 调用
  在严格 CheckJNI 下会崩(`ScopedCheck::CheckMethodID`);非 debuggable 关掉 CheckJNI,与原版一致。

## 运行所需环境(实测可用的配置)

1. **本机联网**:MFi 认证签名走网络(见下),连接阶段平板必须能上网。可用 STA(连路由器 WiFi)。
2. **car 热点**:本机开一个 iPhone 能加入的 AP。本机 ROM 的 `LocalOnlyHotspot` 频段随机(常 2.4G),
   最稳是固定 5GHz:`adb shell cmd wifi start-softap car wpa2 12345678 -b 5`。
   关键约束:**上报给 iPhone 的频段/信道必须与 AP 实际频段一致**,否则 iPhone 扫不到。
3. **iPhone 个人热点关闭**:否则它的单 WiFi 射频被占,无法加入我们的 AP。
4. **蓝牙已配对**:CarPlay 控制通道走蓝牙 iAP2。每次测试建议两端"忘记设备"后重新配对。
5. 本机支持 STA+AP 并发(`wlan0` 上网 + `wlan2` 当 AP)。

操作:打开 LinkCast → 「开始连接」→ iPhone 弹框点允许。投屏后控制面板自动隐藏,
右上角 `≡` 浮动按钮可呼出面板(含取消连接 / 日志)。

## 架构 / 模块

胶水层 `com.linkcast.receiver`:
- `ProjectionService` — 核心前台服务,串起认证/热点/mDNS/状态机/音视频/输入。UI 通过 `StatusListener` 收阶段与日志。
- `ui/MainActivity` — SurfaceView(投屏画面)+ 连接/取消按钮 + 日志面板(投屏后自动隐藏)。
- `transport/BtIap2Transport` — 蓝牙 RFCOMM(iAP2 UUID)连接 iPhone,喂 `income_data`,回写 `output_data`。
- `auth/` — MFi 签名提供方:`NetworkMfiAuthProvider`(网络,主力)、`LocalMfiAuthProvider`(USB MFi 芯片,备用)、`AuthProvider`/Empty。
- `net/HotspotProvider` — 起/广播 car AP 凭据(`setWifiConfiguration`)。
- `net/MdnsDiscovery` — 发现 iPhone 的 `_carplay-ctrl._tcp`,解析地址交给 `onBrowseHandler`。
- `net/AirPlayAdvertiser` — mDNS 广播本机 `_airplay._tcp`(端口 7000),iPhone 据此回连出视频。
- `media/VideoPipeline` — HEVC(回退 AVC)解码到 Surface;输入帧需 HVCC→Annex-B 转换。
- `media/AudioPipeline` — 轮询 `readAudioData(ptr)` 取 PCM 写 AudioTrack。
- `input/InputForwarder` — 触控/按键转发到原生。
- `ReceiverConfig` — 所有可调项读自 `SP` SharedPreferences(可被 adb 覆盖)。

边界层(**包路径不可改**,原生按名绑定):
- `com.example.autoservice.carplay.CarplayNative` — JNI 接口 + `@UsedByNative` 回调。
- `com.google.android.projection.common.BufferPool` / `UsedByNative` — 原生缓存的辅助类。

原生资产:
- `jniLibs/arm64-v8a/`：`libcarplay2_jni.so`(SDK≤25 或 ≥35)、`libcarplay_jni.so`(其它)、`libc++.so`。
- `assets/mfi_cert.bin` — 908B Apple MFi 证书(start 的 cfg 参数)。
- `assets/receiver_res.zip` — 原厂 `classes.dex` + `resources.arsc`,供原生**完整性校验**。

## 关键机制(改动前必读)

- **完整性校验**:原生在 `setDataPath(filesDir, name, archive)` 的第三参 zip 里校验
  `classes.dex` + `resources.arsc` 的 CRC,匹配才生成正确的 jmethodID。我们打包了与 .so 配套的
  那两个条目到 `receiver_res.zip`,首启复制到 filesDir 后指向它。**这份 zip 必须与 .so 同版本**——
  换 .so 必须同步换这份 zip。
- **MFi 认证是网络签名,不是离线自签**:`ChallengeResponse` 把挑战发到 `iamonroad.com`(及备用 IP)
  取回 128B 签名。服务器按 `android_id` 认账(付费/排队)。`NetworkMfiAuthProvider` 复用了一个特定
  设备的 `android_id`——**换设备/换账号需更新它**。需联网,非离线免费。USB MFi 芯片(`LocalMfiAuthProvider`)
  是离线备用通道(目前未接硬件)。
- **完整连接顺序**:BT 连接 → iAP2/MFi 认证(网络签名)→ 起 car AP + 广播 `_airplay._tcp` +
  发现 iPhone 的 `_carplay-ctrl._tcp` → `onBrowseHandler` 交端点给原生 → 原生连 iPhone 控制口(7000)→
  iPhone 回连我们 7000 → RTSP SETUP/RECORD → 视频(HEVC)/音频(AAC→PCM)流。
- **视频**:iPhone 发 H.265/HEVC,NAL 为 4 字节长度前缀(HVCC),喂 MediaCodec 前要转 Annex-B 起始码;
  解码器用视频分辨率(如 1280×720)配置,不是 SurfaceView 尺寸。
- **音频**:原生回调 `onAudioStreamCreate(packed, audioType, nativePtrStr)`,`packed` 是位打包
  (采样率/类型/方向/声道),`nativePtrStr` 是**无符号** 64 位指针(用 `parseUnsignedLong`,
  否则溢出成 0 → 崩)。PCM 经 `readAudioData(ptr)` 取出([12B 头][PCM])。连接时请求 media AudioFocus。

## 可配置项(SharedPreferences "SP",见 `ReceiverConfig`)

`key_set_resolutions`(默认 1280,720)、`etx_wifi_name`/`etx_wifi_pswd`(car/12345678)、
`etx_wifi_band`/`etx_wifi_channel`、`manual_hotspot`、`host_ip`/`host_ports`(MFi 服务器)、
`networkmfi`、`key_audio_buffers` 等。

## 已知限制 / 待办

- **绑定单一设备**:网络 MFi 用了某设备的 `android_id`;换机需替换。
- **依赖第三方服务**:iamonroad.com 若停服/改协议则认证失效(离线方案需 USB MFi 芯片)。
- 热点频段在本 ROM 需手动/adb 固定 5GHz(未做自适应上报)。
- 麦克风/Siri 输入未启用;音频流类型未按 audioType 细分。
- 仍有针对本机/本 ROM 的魔法数字未收敛(协议固定值与设备相关值混在代码里),为下一阶段工作。
