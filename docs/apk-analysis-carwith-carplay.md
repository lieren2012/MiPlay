# base.apk 逆向分析报告：CarWith → CarPlay（及其 ICCOA 化改造评估）

- 样本：`C:\Users\hz\Desktop\base.apk`
- SHA-256：`77C4B6E76D84AF0D9240930385B257433DC6DEBA7087159B7D8C9A23C4B1239F`
- 大小：38,398,906 字节，144 个 zip 条目
- 分析产物：`_analysis/`（解包目录、`src/` 反编译源码、`iamr0s_api.txt` 接口清单、`allstrings.json`）

> 本报告全部结论来自静态分析（未运行样本）。标注「推断」的条目为基于命名/字符串/调用关系的合理推测，需要真机验证。

---

## 1. 样本身份

| 项 | 值 |
| --- | --- |
| package | `com.baidu.carlife.xiaomi`（**冒用小米 CarWith 的包名**） |
| label | `CarWith CarPlay 组件` |
| versionName / versionCode | `8.1.1-carplay.1` / `100011` |
| minSdk / targetSdk | 29 / **37**（compileSdk 37, codename 17） |
| debuggable | `true` |
| BuildConfig | `APPLICATION_ID=com.baidu.carlife.xiaomi`、`BUILD_TYPE=debug`、`GIT_SHA=62ef926`、`NATIVE_HOOK_ID=provider-no-hook` |
| 自有代码命名空间 | `io.github.iamr0s.carplay.****`（1,119 个类） |

`<queries><package android:name="com.miui.carlink"/></queries>`：明确以小米 CarLink/CarWith 系统模块为目标。

### 组件

| 组件 | 说明 |
| --- | --- |
| `io.github.iamr0s.carplay.provider.ProviderActivity` | LAUNCHER，主界面（Compose） |
| `com.baidu.carlife.CarlifeActivity` | **activity-alias** 指向 ProviderActivity（冒充 CarWith 入口） |
| `com.baidu.carlife.mix.xiaomi.XiaomiPermissionActivity` | 占位 Activity（继承原 CarWith 的权限申请入口） |
| `com.baidu.carlife.service.CarlifeConnectService` | **exported**，`foregroundServiceType=0x10`(connectedDevice)，实现 CarLife “provider” 侧（`accept`/`stopProvider`），用于接管 CarWith 会话入口 |
| `io.github.iamr0s.carplay.ConnectionService` | 私有前台服务：真正的 CarPlay/AirPlay 会话主机 |
| `io.github.iamr0s.carplay.provider.ProviderBluetoothReceiver` | 监听 `ACL_CONNECTED`，蓝牙一连上就拉起流程 |

权限面：`BLUETOOTH_SCAN/CONNECT/ADVERTISE`、`NEARBY_WIFI_DEVICES`、`ACCESS_LOCAL_NETWORK`、`CHANGE_WIFI_MULTICAST_STATE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`，以及 **`moe.shizuku.manager.permission.API_V23`**（Shizuku/Dhizuku 特权）+ `<meta-data moe.shizuku.client.V3_SUPPORT>`。

---

## 2. 代码构成（21,743 个类）

| 来源 | 类数 | 说明 |
| --- | --- | --- |
| `androidx.*`（Compose 为主） | 13,650 | `desktop/` 车机 UI 用 Compose Material3 自绘 |
| `org.bouncycastle.*` | 4,584 | AirPlay 配对/加密（Ed25519/X25519/SRP/AES-CTR），含 PQC provider |
| `kotlinx.*` / `kotlin.*` | 2,189 | 协程/序列化 |
| **`io.github.iamr0s.carplay.*`** | **1,119** | 本项目全部逻辑 |
| `rikka.*` + `moe.*` | 53 | Shizuku / Dhizuku 客户端 |
| `com.baidu.*` | 16 | 仅 `CarlifeConnectService` 桩 + `XiaomiPermissionActivity` |

原生库只有两个：`libcarplay_eir_hook.so`（17,808 B）与 `libandroidx.graphics.path.so`。
`de.robv.android.xposed.XposedHelpers` 在 `CarWithSessionHook` 中被引用，但 **APK 内不含 Xposed API** → 推断运行在 Dhizuku-API-Xposed 之类的注入环境里（同作者项目）。

---

## 3. 分层架构

```text
┌─────────────── 内容源层（要换成 ICCOA 的地方） ───────────────┐
│ bridge/*      会话桥 + 自定义 Binder 协议 + CarLife AIDL 直连   │
│ desktop/*     自绘车机桌面（Compose）+ Shizuku 虚拟屏/投屏/触控 │
│ network/*     mDNS(自实现) / _carplay-ctrl 广告 / CarLink 发现  │
└──────────────────────────┬───────────────────────────────────┘
                           │  Surface / H.264 帧 / PCM / 麦克风 / 触控 / 按键 / 元数据
┌──────────────────────────┴───────────────────────────────────┐
│ airplay/*   AirPlay2 发送端：RTSP、配对(SRP/Ed25519)、MFiSAP、   │
│             H.264 编码、HID 触控/按键、麦克风、音量、屏幕加密   │
│ protocol/*  iAP2 编解码/探针、无线 bootstrap、NowPlaying        │
│ video/*     帧时间线、触摸报告、按键报告、YUV 拷贝              │
├───────────────────────────────────────────────────────────────┤
│ hook/*      对系统植入原生 hook：BT EIR 改写、HFP 身份伪装      │
│ provider/*  CarPlay “provider” 角色、视频中继、租约/重试        │
│ 证据：NativeHookContract.TARGET_PACKAGE = "com.android.bluetooth"│
└───────────────────────────────────────────────────────────────┘
```

角色判定：`TestScreenSession` 用 `MediaCodec` **编码** 并主动推送视频 → 本 App 扮演 **AirPlay/CarPlay 发送端（iPhone 角色）**；配合 BT EIR 改写 + HFP 身份伪装 + `_carplay-ctrl._tcp` 广告，让车机把它当成 iPhone 并启动 CarPlay。

---

## 4. 内容源侧：CarWith/CarLife 是怎么被接进来的

这是“改成 ICCOA”唯一需要动的坐标。样本用了 **四条并行通道**：

### 4.1 自定义 Binder 桥（本 App ↔ 目标进程）
`bridge/CarWithBridge`（Kotlin `object`）自建协议：

- DESCRIPTOR：`io.github.iamr0s.carplay.CarWithBridge.v1`
- 触控子描述符：`...v1.touch`；唤醒广播：`io.github.iamr0s.carplay.CARWITH_WAKE`
- 事务码：`TOUCH=1, KEY=2, MICROPHONE=3, DISPLAY_RELEASED=4, PCM=5, NATIVE_UI=6, METADATA=7`
- 会话对象 `Session(id, Surface, width, height, fps)` —— **屏幕内容通过 Surface 交接**（CarWith 往这个 Surface 画，本 App 再编码）

### 4.2 直连 CarLife “mixing” AIDL
`bridge/CarWithDirectClient` 注册/绑定下面这些接口（包名 `com.baidu.carlife.mixing.aidl.`）：

```
ICarLifeCastManager      ICarLifeCastCallback     ISystemAudioCallback
IRemoteKeyListener       IRemoteTouchListener     IRemoteAudioListener
```
即：**接管 CarLife 投屏管理器 + 回调音频/触控/按键/麦克风**。`CarWithDirectContract` 里还写死了车机按键名到 keycode 的映射（`播放=8109`、`暂停=8110`、`上一曲=8101`、`下一曲=8102`、`语音=8107/8108`、`通话 Hook Switch=8111`、`通话 Drop=8112`）。

### 4.3 Xposed 级 hook 小米 CarLink
`bridge/CarWithSessionHook` 用 `XposedHelpers.findClass` 处理：

```
com.miui.carlink.castfwk.CarlinkService
com.miui.carlink.castfwk.CarlinkStateMachine
com.miui.carlink.castfwk.CastController
```
字符串证据：`replaying original prepareCast through e2.g → CarlinkService → native state mac`、"Existing native CarLife connection; refusing takeover"、`CarLife session requires CarWith 4.0.14-20260801`。
→ 它劫持小米 **CarLink cast 框架**的 `prepareCast` 状态机，把会话重放到自己的 Surface 上。**这条就是小米私有的对接点，也是 ICCOA 改造中最需要重写的一条。**

### 4.4 原生 Bluetooth hook
`hook/NativeEirHook` + `libcarplay_eir_hook.so`：按 **硬编码 RVA**（`WriteEIR rva=0xb02c80`、`SDP_AddSequence rva=0x8ceaa8`）在 `libbluetooth_jni.so` 内打补丁，改写 EIR 让本机在蓝牙层宣告 CarPlay/SPP 服务，并用 SDP 注册手机身份（`mode=observe|modify`，租约 180s）。字符串里出现 `checked Xiaomi target only`、`ro.arch`、`exynos9810` → **强机型/ROM 绑定**。

### 4.5 网络发现
- `network/LegacyCarDiscovery`（自实现 mDNS 应答器）：本机广告
  `instance = "CarPlay Android <12位随机>._carplay-ctrl._tcp.local."`、`host = "carplay-<suffix>.local."`，并用 MulticastLock 收发；含名字冲突探测（probing/collision）、TTL 缓存（`MdnsCache`）。
- `network/ModernCarDiscovery`：走系统 NsdManager 浏览。
- `network/CarNetwork`：`ConnectivityManager`/`WifiManager` 侧的网络绑定与地址选择。

### 4.6 本地自绘内容（非 CarWith 路径）
`desktop/*`（455 类）= 一套自绘车机桌面：`DesktopApp/Apps`、`DesktopMusic/MusicLibrary/MusicLyrics(NeteaseLyrics)`、`DesktopMapHost`+`IMapDisplayService`（高德/百度车机版地图片）、`DesktopPresentation`、`Wallpaper`、`BatteryInfo`、`DesktopSettings`。
`desktop/ShizukuDisplaySession` + `ProjectedApps` + `ProjectedTouchService`(+`res/xml/projected_touch_service.xml`)：用 **Shizuku** 创建虚拟显示、把真实手机 App 投到车机屏并注入触控。
→ 也就是说，本 App 有 **两套内容源**：① 钩住 Xiaomi CarWith/CarLink；② 自己渲染 + Shizuku 投屏。

---

## 5. CarPlay 侧（需要保留的部分）

| 模块 | 内容 |
| --- | --- |
| `airplay/RtspConnection` | 自实现 RTSP/1.0 客户端（`CSeq`/`User-Agent: AirPlay/320.17`，拒绝 chunked） |
| `airplay/PhoneIdentity`、`PhonePairing`、`SetupProof`、`AirCrypto` | AirPlay2 配对（SRP、Ed25519/X25519、HKDF 类派生） |
| `airplay/MfiAuthSetup`、`MfiSecurity` | MFiSAP `et=16` 密钥封装 + `AES/CTR/NoPadding` 屏流加密；日志明示 `trust-chain-verified=false / development-trust=true`（即**跳过 MFi 硬件认证**，与开源 AirPlay 实现的做法一致） |
| `airplay/TestScreenSession` | 会话主机：MediaCodec 编码、HID 触控/按键、音频/麦克风会话、屏幕加密 |
| `airplay/MicrophoneSession`、`AudioToneSession`、`LivePcmBuffer`、`PcmAudioFormat` | 上行音频/麦克风通道 |
| `protocol/Iap2Codec`、`Iap2Probe`、`WirelessBootstrap`、`WirelessConfig`、`ControlDecoder`、`NowPlayingBridge` | iAP2 报文编解码、无线引导、控制总线、媒体元数据 |
| `video/*` | `SurfaceFrameTimeline`、`TouchReports`、`KeyReports`、`VideoPackets`、`YuvPlaneCopy` |
| `hook/HfpIdentityHook`、`BluetoothHook` | HFP 身份与蓝牙控制面伪装 |

`ConnectionService` 常量：`PHONE_UUID`、`PHONE_EIR_UUID`、`ACCESSORY_UUID`，持有 `BluetoothServerSocket` 主动 `acceptPeer(...)`（作为被车机连接的蓝牙服务端）。

---

## 6. 结论：接缝（seam）在哪里

把「内容源」抽象出来，样本的耦合点只有 6 个信号：

1. **视频**：`CarWithBridge.Session(id, Surface, w, h, fps)` 或 `CarLifeIntegration.VideoRelay`（H.264 帧）
2. **系统/媒体音频**：`PCM`（`CarWithDirectContract.validPcm`：8–48 kHz、16 bit、单声道/立体声、帧长对齐 2×声道）
3. **麦克风/上行音频**：`MICROPHONE`
4. **下行输入**：`TOUCH=1` / `KEY=2`（坐标按远端分辨率缩放，键值映射表见 4.2）
5. **元数据/状态**：`METADATA=7`、`NATIVE_UI=6`、`DISPLAY_RELEASED=4`、`NowPlayingSnapshot`
6. **生命周期**：会话建立/释放、Surface 释放、重连

`bridge-core` 的 `MediaFrame / InputEvent / AudioPacket` 与这 6 项**一一对应**，说明本仓库既有模型可以直接作为“ICCOA 车机端适配器”的落点。

---

## 7. “改成 ICCOA 转 CarPlay” 的可行性与路线

### 7.1 先说不能做的事

- **无法用 smali 补丁把这个 APK 改成 ICCOA**。理由：① 无源码、Compose + 1,119 个 Kotlin 类、方法名/行号被 Kotlin 内联与 lambda 打散；② 要换的是**一整套协议栈**（小米 CarLink `castfwk` 状态机 + Baidu CarLife mixing AIDL + 自定义 Binder + 原生 BT patch），不是几个常量；③ ICCOA Carlink 与 CarLife/CarWith 的会话模型、加密、媒体封装都不同，等于重写内容源层。
- 环境层面当前机器**没有 JDK**，apktool/baksmali 都不可用；即便装上，反编译-改-回编译也只适合小改（改包名、改广告名、改字符串），不适合换协议。

### 7.2 可行路线

**路线 A（推荐）：把本仓库（Rust 干净室）做成 ICCOA→CarPlay 桥，把 APK 当作行为参考。**
- 保留 APK 已验证的 CarPlay 侧行为清单（第 5 节）：RTSP 流程、配对、MFiSAP 跳过策略、HID 报文、iAP2 引导、`_carplay-ctrl` 广告、EIR/HFP 伪装要点。
- 新写 `iccoa-hu-adapter`：实现 **ICCOA Carlink 车机端**（发现 → 会话建立 → 视频/音频/输入/状态），对外只暴露第 6 节的 6 个信号。
- 与既有 `carplay-tx-adapter` 对接，格式仍是 MediaFrame/InputEvent/AudioPacket。
- 优点：法律干净（不反编译复用代码、不重打包他人 APK）、可控、可测试；缺点：ICCOA Carlink 需要拿到规范或做干净室行为采集。

**路线 B（同机改造）：保留 APK 的 CarPlay 侧，另写一个 Android 模块做 ICCOA 车机端，用 Binder/共享内存把 6 个信号喂给它。**
- 现实约束：CarPlay 侧写在 Compose App 内部的对象图里（`ConnectionService` 静态持有 `TestScreenSession`），没有对外接口；要注入只能再上一层 Xposed/patch，成本高且脆弱。除非作者能提供源码。

**路线 C（小改，可能就是你现在的短期目标）**：只换“宣告名/外观”，例如把 `CarPlay Android <id>`、`CarWith CarPlay 组件` 之类改成 ICCOA 相关标识。这改不到协议，只是表面。

### 7.3 若要走 A/B，ICCOA 侧还需要确定的产品语义

- **方向一**：本机（小米/OPPO/vivo 等手机）用厂商 **ICCOA Carlink 发送端**投到本 App，本 App 作**车机端接收**，再以 CarPlay 发送给车 → 手机不再需要 CarWith，也不依赖小米私有 hook。
- **方向二**：本 App 只做**车机端**（跑在车机/盒子上），接收任意 ICCOA 手机的 Carlink，输出 CarPlay。

两者对“内容源”接口一样，但运行位置、发现流程（谁发 mDNS、谁开 AP）完全不同，需要先定。

---

## 8. 风险与边界（事实陈述，非道德评判）

1. **包名冒用**：`com.baidu.carlife.xiaomi` + `com.baidu.carlife.CarlifeActivity` 是冒充小米/百度 CarWith 的身份。对外分发这种重打包件有明确的商标/著作权风险；自用研究另说。
2. **原生 hook**：`libcarplay_eir_hook.so` 按 RVA 补丁系统蓝牙库，属 ROM/机型强绑定，换机即失效，且有稳定性风险（`Rejected: runtime Build ID/executable segment mismatch`）。
3. **依赖特权**：`moe.shizuku.manager.permission.API_V23` + 可能的 Xposed/Dhizuku 注入 → 需要 root/Shizuku，普通用户无法部署。
4. **Apple 侧**：该实现走的是开源 AirPlay 实现同样的“跳过 MFi 信任链”路线，没有伪造 Apple 硬件凭据；但如果要把它做成商品，AirPlay/CarPlay 的授权问题需要单独评估。
5. **行车安全**：与 README 一致——车内调试必须在安全停车状态完成，不得影响原车倒车影像/仪表/空调。

---

## 9. 建议的下一步（按投入排序）

1. 明确 7.3 的方向（A 还是 B，接收端语义）。
2. 采一份 ICCOA Carlink 干净室行为记录（抓包 + 日志），产出“发现/握手/媒体/输入”四组时序，落到 `docs/iccoa-*.md`。
3. 在 `bridge-core` 增加 `iccoa` 适配器 trait，并把第 6 节 6 个信号定义成稳定接口（含单测）。
4. 用 `bridge-lab` 做 ICCOA↔CarPlay 的仿真闭环，再上真机。

---

## 附：分析可复现命令

```powershell
# 解包
#   python -c "import zipfile;zipfile.ZipFile('base.apk').extractall('apk')"
# 清单/权限/组件（pyaxmlparser）
#   python -c "from pyaxmlparser import APK; a=APK('base.apk'); print(a.package)"
# 类表（自写 dex 解析，_analysis/dexparse.py）
# 反编译（androguard DAD，_analysis/p11.py → _analysis/src/*.java）
```

## 更正（2026-10-10）：免 root 的 CarPlay 身份机制

先前结论「必须 root / 注入」是**错误的**。样本里其实写明了：
`BuildConfig.NATIVE_HOOK_ID = "provider-no-hook"`，且日志明确说
`Identity helper SDP registered ...; actual EIR requires HCI confirmation` —— 原生 EIR 只是可选增强。

真正的机制全在 App 层（`ConnectionService` 反编译证据）：

```java
UUID PHONE_UUID     = "00000000-deca-fade-deca-deafdecacaff"
UUID PHONE_EIR_UUID = "00000000-deca-fade-deca-deafdecacafe"
UUID ACCESSORY_UUID = "2d8d2466-e14d-451c-88bc-7301abea291a"

adapter.listenUsingRfcommWithServiceRecord("CarPlay Probe", PHONE_UUID)
adapter.listenUsingRfcommWithServiceRecord("CarPlay Identity Probe", PHONE_EIR_UUID)
adapter.getRemoteDevice(addr).createRfcommSocketToServiceRecord(ACCESSORY_UUID)
```

即：**App 通过 `listenUsingRfcommWithServiceRecord` 让系统蓝牙把 Apple 的 CarPlay UUID 写进 SDP 记录**，
车机 ACL 连上时（`ProviderBluetoothReceiver` 监听 `ACL_CONNECTED`）流程启动，App 再反向用
`ACCESSORY_UUID` 连车机。**不需要 root、不需要刷机、不需要额外模块。**

对应实现：`android/iccoa-sink/java/.../CarplayBluetooth.java` + `CarplayBluetoothReceiver.java`。
