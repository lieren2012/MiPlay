# 手机端 APK：把本机伪装成 Apple 发送端（CarPlay 侧）

产物：`C:\Users\hz\Desktop\iccoa-sink-0.1.0.apk`
包名：`org.carplaytocarlife.iccoa`，debug 签名，minSdk 24 / targetSdk 34
构建：`powershell -ExecutionPolicy Bypass -File android\build-iccoa-sink.ps1`
源码：`android/iccoa-sink`（无 Gradle，aapt2 + javac + d8 + apksigner）

## 1. 它现在做了什么

界面只有四个按钮：Start / Stop / Clear log / Input。

点 Start：

1. 弹出系统投屏授权（MediaProjection）。
2. 前台服务启动（`connectedDevice | mediaProjection`）。
3. **蓝牙身份**：用 App 层 API 注册两条 SDP 记录，并把本机蓝牙名改成 `iPhone`。
   不 root、不改系统分区、不注入系统进程。
   - `CarPlay Probe` → `00000000-deca-fade-deca-deafdecacaff`
   - `CarPlay Identity Probe` → `00000000-deca-fade-deca-deafdecacafe`
   - 出向连接车机用的 `2d8d2466-e14d-451c-88bc-7301abea291a`
4. **发现**：同时用 mDNS 浏览 `_airplay._tcp` / `_raop._tcp`。这条只是退路：
   正常情况下第 5、6 步拿到的 endpoint 更准。
5. **iAP2 引导**（新增）：链路建立后按车机期望的 iAP2 会话走
   `0x1D00 → 0x1D01 → 0x1D02 → 0xAA00 → 0xAA01 → 0xAA02 → 0xAA03 → 0xAA05`，
   然后公告设备名/uuid/transport 并发出 `0x5702` 索要 Wi-Fi 配置。
   车机用 `0x4301`（或 `0x5703`）把 **SSID、密码、安全类型、信道，以及 AirPlay
   receiver 的 IP 和端口**一起交过来。报文表见 `docs/carplay-iap2-bootstrap.md`。
6. **入网**（新增）：拿到凭据后用 `WifiNetworkSuggestion`（Android 10+，无需弹窗）
   或旧版 `addNetwork` 连上车的热点；连上之后再开会话，直接用 `0x4301` 里给的
   endpoint，mDNS 只作为车机没应答引导时的退路。
7. **会话**（本轮按参考实现重排）：`POST /auth-setup`（MFiSAP 密钥交换）→ 或退回
   `/pair-setup ×3 + /pair-verify ×2`（SRP 3072 位 g=5、X25519、Ed25519、PIN `3939`，
   **TLV8 报文**，见 `docs/carplay-airplay-session.md` 6i 节）
   → `SETUP /setup` → `GET /info` → `GET /event`（第二条连接）→ `POST /command modesChanged`
   → `SETUP /`（屏幕流 `type=110`）→ `RECORD /`。
   `POST /command modesChanged` 发的是参考实现的**模式快照**（资源 1=屏幕、2=音频，
   entity 1=手机占用/2=车机占用，默认屏幕归手机）；车机的 `changeModes` 是**屏幕归属移交**
   （transferType 1/2/3/4）：**能处理**的用新快照回（`{status:0, params:…}`），**处理不了**的
   按参考实现回 501 —— 详见 `docs/carplay-airplay-session.md` 6d 节。
   报文表见 `docs/carplay-airplay-session.md`。
   - 屏幕密钥：`/auth-setup` 用共享 X25519 秘密推一个 AES-CTR 包装流，把随机 baseKey/baseIv
     包成 `{et:16, ekey, eiv}` 并进 `/setup`；屏幕流用
     `SHA-512("AirPlayStreamKey"+n || baseKey)` 与 `SHA-512("AirPlayStreamIV"+n || baseIv)`
     分别派生密钥和 IV，整会话一个连续 keystream
   - 帧封装：128 字节头（偏移 0 密文长度、偏移 8 **NTP** 时间戳，均 LE），正文从 128 开始
   - 开流前先发一帧未加密的 **config 帧**（偏移 4=1、偏移 6=4、偏移 16/20 = 宽高 float、
     偏移 128 起是 **AVCDecoderConfigurationRecord**，不是裸 SPS/PPS），
     否则车机解码器拿不到配置（黑屏最常见的原因）
   - 端口由**车机在 SETUP 响应里分配**，发送端只交 `streamConnectionID` 和 `latencyMs=100`
   - 编码器：Baseline profile、禁 B 帧、I 帧间隔 2 秒、码率按 `w*h*fps/8` 夹在 2–12 Mbps、
     静止画面用 `repeat-previous-frame-after` 保持出帧；**编码尺寸取手机屏幕尺寸 × scale%**
     （默认 100，向下取偶、下限 128），**不是**车机 `/info` 报的尺寸；帧率取
     `min(本机 30, 车机公告值)`，只能被车机压低。车机公告值 = `/info` 里
     `displays[type == 0].maxFPS`（字段不在时 15，依据见 `docs/carplay-airplay-session.md` 6g 节）
   - 音频：另起一次 `SETUP /` 协商（`type=96 / audioType="media"`）。**先报哪个 PCM 格式由车机
     公告的 `audioOutputFormats` 掩码决定**（参考实现就只报那一个），其余候选跟在后面作退路，
     拿到 dataPort 后用 UDP 发 ChaCha20-Poly1305 包 —— 12 字节大端头
     + AAD=头部 4..12 + nonce 尾部 8 字节（计数器**小端**）。
     密钥是 `HKDF(shared, "DataStream-Salt"+n, "DataStream-Output-Encryption-Key")`；
     **没有 `/auth-setup` 就没有这个密钥，此时宁可不发音频也不发车机解不开的包**
8. **视频源**：拿到投屏授权就把**本机屏幕**镜像进 H.264 编码器
   （`ScreenSource` = MediaProjection + VirtualDisplay → `SurfaceEncoder` 的输入 Surface）；
   没有授权就退化成合成的滚动渐变图案，管线照样跑。
9. **触控（真实路径 + 实时）**：`requestUI` 里车机把每个 HID 设备的**原始描述符**交给我们，
   `hidSendReport` 里的报文按该描述符的位域解码（`HidDescriptor`），解出来交给无障碍注入。
   描述符解析不了就只记日志不注入。
10. **事件通道 + iAP 隧道**：车机在 `GET /event` 那条连接上主动下发请求；
   RECORD 之后隧道 arm，等车机先发第一条 `iAPSendMessage`，收到后把 iAP2 收发切到隧道
   （`POST /command` 信封），**蓝牙保留作退路**；车机命令共六种（`requestUI`/`hidSendReport`/
   `forceKeyFrame`/`requestSiri`/`iAPSendMessage`/`changeModes`），做不成的按参考实现一律回 501。
   日志把每个报文的 plist 字段打全，任何 6 字节 data 字段都按 HID 触摸解码后交给
   `TouchInjector`，用无障碍手势注回本机。
11. **Input 按钮**：跳到无障碍设置，用来打开注触服务。

桌面自测（`SelfTest*.java`，桌面 JDK 直接跑，不打进 APK）：**536 项全绿 / 0 失败**，20 组。
其中两组是"扮演车机"的：`SelfTestBootstrap`（一次性自签 P-256 证书 → 挑战 → 签名 →
`0x4301` 解出 Wi-Fi 配置，含各类拒绝路径）和 `SelfTestAuth`（`/auth-setup` 全程：
车机侧独立推同一个 AES-CTR，验证它从 `ekey` 解出来的确实是手机用的 baseKey，
再验证两边派生的屏幕流密钥一致）。`SelfTestClient` 用环回连接跑完上述整套 RTSP 顺序，
让"车机"从事件通道推一条 HID 报文检查它变成一次触摸，还让"车机"**先拒掉第一个 PCM 报价**
再接受第二个，用来看音频协商循环真的会往下试。`SelfTestAudio` 盯音频包本身
（小端计数器、尾部、AAD、长度），`SelfTestAuth` 用独立写一遍的 HKDF 校验 DataStream 密钥。
`SelfTestScreen`（20 项）专门盯屏幕流封装：NTP 时间轴（含"写成微秒会落在错误半区"的反证）、
config 帧的头布局与明文参数集、SPS/PPS 从关键帧里提取。

跑法（推荐，同时看"退出码"和"是否打印 SELFTEST 结论"，能抓到中途崩溃的组）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-selftests.ps1
```

手动跑法：

```powershell
$jdk='C:\carplaytocarlife\tools\android\jdk-17.0.20.1+1'
$bc='C:\carplaytocarlife\tools\libs\bcprov-jdk18on-1.78.1.jar'
$aj='C:\carplaytocarlife\tools\android\android-36\android.jar'
$out='C:\carplaytocarlife\android\build\selftest'
New-Item -ItemType Directory -Force $out | Out-Null
$src = @(Get-ChildItem -Path 'C:\carplaytocarlife\android\iccoa-sink\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$src += @(Get-ChildItem -Path 'C:\carplaytocarlife\android\build\gen' -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$jdk\bin\javac.exe" -encoding UTF-8 -nowarn -classpath "$aj;$bc" -d $out $src
foreach ($t in @('SelfTestBootstrap','SelfTestClient','SelfTestControl')) { & "$jdk\bin\java.exe" -cp "$out;$aj;$bc" "org.carplaytocarlife.iccoa.$t" }
```

## 2. 真机怎么跑

1. `adb install -r iccoa-sink-0.1.0.apk`，或直接在手机上点安装。
2. 打开 App，按提示给蓝牙 / 附近设备 / 通知权限。
3. 建议：点 **Input** → 无障碍 → 已下载的应用 → `ICCOA to CarPlay` → 打开注触服务。
4. 车机蓝牙菜单里发起配对，此时手机在车机眼里应该叫 `iPhone`。
5. 回到 App 点 **Start**，允许投屏授权。
6. 盯日志区。

## 3. 跑完把这些日志发回来

```text
SDP registered: ...
iAP2 session 1 open; starting the phone handshake
auth-setup unavailable (...); falling back to pairing      ← 或 MFiSAP certificate-bytes=... signature-valid=true
session established: ... (uuid ...)
GET /info fields=[...]
the car's display 1920x720 maxFPS=30 (displays[1].type=0)   ← 或 flat / no display size
event channel open
modesChanged accepted; reply fields=[...]
streams agreed: screen type 110, connection 1, port ...
the car refused PCM/48000/2ch format=32768: ...
the car announced PCM formats mask=0x... (it can take PCM/44100/2ch format=2048)
audio agreed: PCM/44100/2ch format=2048, connection 2, port ...
video tunnel sent the config frame (... bytes of parameter sets, 1280x720)
video tunnel sent N frames (last ... bytes, encrypted to ...)
iAP2 <- 0x1D01 the car sends [...] and receives [...]; modern=true
bootstrap <- 0xAA01 certificate bytes=... chain=... key=EC ...
bootstrap <- 0xAA03 signature valid=true
the car offered its network: ssid=... credential=present security=... endpoints=[...] port=...
Wi-Fi: suggested ... accepted=true
Wi-Fi: joined ... as 192.168.x.y
NSD registered ... / mDNS browsing _airplay._tcp
AirPlay connected to ...
<- 200/403/453/500 ...          <- 状态码最关键
car rejected SETUP: ...
SETUP accepted, session=...
control channel listening on tcp/...
control channel connected from ...
car touch ... -> ...
video path running ... / surface encoder produced N access units
```

### 3o. 配对报文一直是错的形状：plist vs TLV8

这轮核 `pair-setup` / `pair-verify` 的协议体，发现**我们的配对根本没按线上格式写**。

参考实现（`PhonePairing`）发的是 **TLV8**：`Content-Type: application/pairing+tlv8`、
`X-Apple-HKP: 2`（`/pair-verify` 再带 `X-Apple-PD: 1`），body 是「1 字节类型 + 1 字节长度 + 值」的
TLV 项，超过 255 字节的值拆成多个同类型项（SRP 的公钥就是 384 字节 = 2 项）；每步都带 state（项 6），
回包必须是下一步的状态。本仓库发的是 **binary plist** + `application/octet-stream`，字段名还是自己起的
（`method`/`pk`/`salt`/`proof`/`data`）。

为什么一直没被发现：**假车是我们自己写的**，它按 plist 回，于是这条链在自己家里永远是绿的。
真车上这一步会直接拿到错误码（或者干脆没有回包），而它还是 `/auth-setup` 缺失时唯一的退路。
这次连同三处一起修了：

1. **TLV8 收发**（新的 `Tlv8`：编码带 255 字节分片、解码自动拼接、缺项/长度不符/截断都报错）；
2. **四个 nonce 各不相同**：`PS-Msg05`（控制器→配件）、`PS-Msg06`（配件→控制器）、
   `PV-Msg02`（配件→控制器）、`PV-Msg03`（控制器→配件），都是「4 个 0 字节 + 标签」的 12 字节，
   来自参考实现的 `AirCrypto.label`。我们原来双向用同一个计数器 nonce；
3. **签名覆盖的 transcript 补全**：pair-verify 的签名要盖三个字段
   （`自己的临时公钥 || 配对 id || 对方的临时公钥`），我们原来只盖了前两个，而且**从不验证**车机那份。
   现在两边都验（车机那份用 pair-setup 拿到的长期公钥）。

假车也一起改成 TLV8（并且**会把形状不对的配对请求**直接拒掉），所以这套形状以后再退化，测试会立刻红。
整条链路在自测里跑通，日志逐项可查：

```text
pairing /pair-setup state 1->2 items=[6, 2, 3]
pairing /pair-setup state 3->4 items=[6, 4]
pairing /pair-setup state 5->6 items=[6, 5]
pairing /pair-verify state 1->2 items=[6, 3, 5]
pairing /pair-verify state 3->4 items=[6]
```

**顺带发现一个我们完全没有的步骤**（已记进 `docs/carplay-airplay-session.md` 6j 节）：配对成功后，
参考实现会用 pair-verify 的共享秘密把**整条会话连接**加起来：
`readKey = HKDF(shared, "Control-Salt", "Control-Read-Encryption-Key")`、写出同理（HAP 帧格式）。
我们没有这层，于是配对成功后会在日志里明确写一行 `the control channel is NOT encrypted: ...` ——
真车上如果"配对过了但下一个请求就被挂断"，看这一行就知道是不是卡在这里。
### 3n. 音频格式：参考实现从来不猜，我们原来在猜

接着读参考实现的音频路径（`AudioTonePackets.select` / `AudioToneSession`），发现我们**猜错了做法**：

**参考实现只报一个格式，而且是车机自己说的那个。** 它从车机的 stream 描述里筛出
`type == 96` 且 `audioType == "media"` 的条目，把每条带的 `audioOutputFormats` 按位或成 mask，
再按候选顺序取**第一个** bit 落在 mask 里的格式，只发这一条 SETUP。mask 里一个都没有就直接抛错：
`车机没有公告可用的 type96/media PCM 格式；不猜测格式`。

本仓库原来做的是"四个候选按顺序试，车机收哪个用哪个"——那不是参考实现的做法，而且有个阴险的后果：
**车机可能接受一条它其实解不了的 SETUP**（不少栈只校验字段存在），于是我们这边打出
`audio agreed: ...` 以为谈成了，车机那边一声不响 —— 日志上完全看不出问题。

现在按参考实现来，但留了退路：

- 车机公告的掩码里有匹配的格式 → **它排第一位**；
- 其余候选跟在后面 —— 公告过期、或我们读错字段时不至于完全没声音（代价是多一条被拒的 SETUP）；
- 车机什么都没公告 → 按原顺序试，并在日志里说清楚这是在试。

两种形状都有日志（`the car announced PCM formats mask=0x...` / `the car announced no audio formats...`），
`check-run-log.py` 也加了 `audio-formats` 步骤和"车机没公告格式"这条告警 —— 真车上一眼能看出走的是哪条路。

顺带确认了音频 SETUP 的 8 个字段（`type/audioType/audioFormat/streamConnectionID/audioLatencyMs/spf/sr/ct`）
与参考实现**逐字段一致**（`spf = rate / 200`、`ct = 1`、`audioLatencyMs = 100`），屏幕流的 3 个字段
（`type/streamConnectionID/latencyMs=100`）也一致 —— 这两处不用改。

**还挖出一条我们完全没有的通道**（已记进 `docs/carplay-airplay-session.md` 6a 节）：参考实现会先试
`SETUP` 一条 `type = 130` 的流（带 `seed` 和固定 uuid），车机分配端口后在那条 socket 上跑加密的 iAP2
（`DataStream-Salt<n>` 那套密钥），被拒才退回 `/command` 信封。本仓库只用信封；真车上如果
`<- 200` 之后迟迟没有 `iAPSendMessage`，下一步就是把它补上。
### 3m. 四处"以前只能猜"的地方，这轮从参考实现里挖出来定死了

真车不在手上，但那个**能在真车上跑**的参考实现就躺在 `_analysis/src` 里。这轮不猜了：
把它读 `/info`、处理车机命令的代码逐行读完，改了四处**猜错就会静默降级**的地方。

**① `/info` 的帧率上限在 `displays[].maxFPS`，不在顶层。** 参考实现
（`TestScreenSession$run$2`）读的是 `displays` 列表里 `type == 0` 那一项，取
`widthPixels` / `heightPixels` / `maxFPS`（字段不在时**按 15 算**）；`widthPixels` 缺失直接抛
`Missing display width`。本仓库之前在**顶层**找 `maxFps / max-fps / fps / refreshRate` ——
连大小写都不对。真车按参考实现的形状回包时，这个上限会被整条丢掉：编码器一直按手机自己的帧率跑，
车机只能自己丢帧。现在按参考实现的顺序读（`type == 0` 优先，没有就取第一个能读的），并把用的是
**哪种形状**写进日志（`the car's display 1920x720 maxFPS=30 (displays[1].type=0)`）；
平铺字段保留作退路，一条日志就能看出真车走的是哪条。

**② 车机发来的命令一共六种**，全部有出处（`invokeSuspend$command` 的分支）：`requestUI`、
`hidSendReport`、`forceKeyFrame`、`requestSiri`、`iAPSendMessage`、`changeModes`。本仓库六种都有分支，
日志里也都会留下痕迹。

**③ 处理不了的 `changeModes` 要回 501，不是 400。** 参考实现的 `RtspConnection.reply` 在这条路径上
只有两种答案：能处理 → 200（body 是 `{status:0, params:<新状态>}`），处理不了 → **501 Not Implemented**
（`commandResponse` 返回 null，`command` 布尔处理器也返回 false，于是回 501）。本仓库原来回 400：
车机只会看到一个它没预期的拒绝，我们自己的日志也记错了"参考实现会怎么答"。已改成 501。

**④ 不认识的命令类型同样回 501。** 参考实现对未知 type 的原文是
`AirPlay command unsupported type=...; response=501`；本仓库原来回 200（"记个日志就当没事"）。
现在也回 501，并在日志里带上类型名（真车一发新命令，这一行就是线索）。
唯一保留 200 的是**没有 type 的报文** —— 那是车机的裸 `POST /feedback`，参考实现对这个路径也只回 200。
**⑤ `hidSendReport` 也要"如实"回码。** 参考实现只有在报文**真的解析成了触摸点**时才回 200
（`return 1`），三种情况回 501：设备没有对应的描述符（`HID unsupported device=...; no matching
touch/key descriptor; response=501`）、描述符不是触摸屏、报文按描述符解不出来
（`HID touch decode failed device=...; response=501; video retained`）。
本仓库原来**一律回 200** —— 车机永远不知道自己那份报文白发了，而这条最要命的链路上
（触控不通）日志里也看不出是哪一步断的。现在按参考实现回：解出触摸点 → 200，其余 → 501，
并且每条拒绝都写在日志里。

**⑥ 触控的 `tip`/`contact` 字段名不用猜。** 参考实现里根本没有硬编码的 usage —— 它把车机在
`requestUI` 里给的**描述符**编译成一个解码器（`TouchReports` / `KeyReports`），再用它解每一份报文。
也就是说 `tip usage`、`contact id`、坐标位宽这些**都是车机自己声明的**，谁猜谁错。
本仓库本来就是描述符驱动（`HidDescriptor` + `HidTouch`），这轮只是把"回码"补齐；
顺带确认了参考实现的按键路径（`KeyReports`）本仓库**没有**：所以方控/按钮的报文现在会得到
一条明确的 501 和日志（`is not a touch screen` / `no descriptor registered`），而不是静默吞掉。

顺带把两边的"引用"补齐：`docs/carplay-airplay-session.md` 的 6d/6g 两节改写成了这三种形状的对照表，
`scripts/check-run-log.py` 增加 `display` 步骤与两条告警（`names no display size`、
`which this side does not implement; answering 501`），并且都过了 `--verify-patterns`。

自测从 500 项涨到 **515 项**，其中新增的断言专门盯这几处：`displays` 里 `type == 0` 的挑选
（假车故意把 type 0 放在**第二项**）、缺 `maxFPS` 时按 15、缺像素尺寸时不猜、能处理的 `changeModes`
回 `{status:0, params}` 且 `entity` 跟着变、处理不了的与未知类型都回 501。
### 3l. 三个关于"输入"的发现：自测语料、构建时间戳、散文里的数字

这三件事都不在协议里，但都会让**结论**失真。

**① 覆盖率审计的语料一直是空的。** `scripts/run-selftests.ps1` 原来只把**失败**套件的输出打到控制台，
通过的套件输出直接丢掉；而覆盖率审计是对 `--corpus` 给的那份文本做子串搜索。于是它把 306 条日志
**全部**报成"从没执行过" —— 数字难看，但不是真的。现在 runner 把每套的完整输出写进
`reports\selftest-all.log`（控制台仍然只留失败和总计），审计自己也加了闸门：语料里找不到套件标记
就直接拒绝（退出码 2），不再报一份"全都没测过"的假警报。改完之后真实数字是 154 条桌面到不了 +
57 条故障分支（见 3h）。

**② 两次构建给出两个哈希。** 顺手验了一下可复现性，结论是**不可复现**：同一份源码连跑两次，
APK 的 SHA-256 不同（zip 条目的时间戳来自构建时的时钟）。也就是说，之前记下来的哈希
**不标识任何东西** —— 它只标识"那一次构建"。现在构建脚本在 zipalign 之前把所有条目的时间戳统一成
1980-01-01，于是同一份源码给出同一份字节：

```text
build 1: 3758C8DE4B8F6531D65296BF1ACEFB7BF2BBA45707733018336EADA5C8992C8F
build 2: 3758C8DE4B8F6531D65296BF1ACEFB7BF2BBA45707733018336EADA5C8992C8F
```

（同一轮验过这一步没破坏签名和对齐：`apksigner verify --verbose` 报 v2/v3 通过，
`zipalign -c -v 4` 报 Verification succesful，`aapt2 dump badging` 的包名/版本/`targetSdkVersion=34` 不变。）

这是**3l 那轮实测**的记录（上表的值已经跟着 3m 的改动更新过一次），不是长期有效的指纹：
源码一改，哈希就变，所以构建脚本每次都会打印当次的哈希 —— 请以构建输出为准。

**③ 顺手人工核了一遍散文里的数字。** 3k 的工具**核不了**"每 2 秒"这种声明（那需要一个读者，不是一个正则），
于是这轮把文档里所有"N 秒 / N 次"逐个对着代码常量验：心跳 2 秒（`FEEDBACK_INTERVAL_MILLIS = 2000`，
每 15 条记一次 = 约 30 秒）、广告 2 秒（`ANNOUNCE_INTERVAL_MILLIS = 2000`）、视频停滞 20 秒
（`STALL_MILLIS = 20_000`）、I 帧 2 秒（`I_FRAME_INTERVAL_SECONDS = 2`）、蓝牙出向重试 20 秒 / 最多 3 次、
iAP2 谁先发起等 4 秒（`SESSION_WAIT_MILLIS = 4000`）—— 全部对得上，**除了两处**：
`docs/runbook.md` 的排障表（读者当日志对照表用，是现在时的声明）和本文 3d 节里都写着"会话重试间隔 5 秒"，
而代码在 3j 那轮已经改成 20 秒。两处都已修正（3d 那处保留了原数字并标注"这是编的"，因为它是那一轮的历史记录）。

数字类的声明没法自动化，只能每次改常量时顺手正一遍 —— 这轮就是有人真去数了才发现的两处。

### 3k. 文档一致性审计：文档也会"漂"

`docs/` 是最容易烂的一层：代码里改了报文 ID、挪了文件，文档还照着旧的写，而且没人会去读第二遍。
新增 `scripts/audit-docs.py` 把两边的说法对起来：

- 文档里出现的**报文 ID**（`0x1D01` 这种）、**mDNS 服务名**（`_airplay._tcp`）、**端口号**
  （7000/710x/5353/5000）在源码里找不到出处 → 报出来；
- 文档里用反引号引的**文件路径**在仓库里不存在 → 报出来。

两类豁免是写死的，也是诚实的：手机上的路径（`/data/app/...`）、用户给的样本（`base.apk`）、
基准测试里按哈希描述的下载包（`DiPlay-0.2.16.apk`）本来就不属于本仓库；`reports/` 是运行输出目录
（文档自己教你跑命令生成它），也不检查。路径允许省略中间层（`java/.../Iap2.java`）或只写文件名，
但**改了名、挪了目录的文件一定会被抓到**：把 `Iap2.java` 换成它不在的目录（或改一个字母）当场就报。

同前两个审计，它自带 `--selfcheck`：往 `docs/` 里种一条假声明（假报文 ID + 假服务名 + 假端口 +
假文件路径），跑一遍，要求四种形状**全部**被抓到。第一版就漏了种下的假路径 —— 探针里忘了加反引号，
于是路径检查根本没被走到，自检当场把它挡下来了（这正是加自检的理由）。

真实审计的结论：

```text
---
0 claim(s) the code does not back
```

跑法：

```powershell
& _analysis\venv\Scripts\python.exe scripts\audit-docs.py --selfcheck   # 先验工具
& _analysis\venv\Scripts\python.exe scripts\audit-docs.py              # 再验文档
```

范围说明：这个工具只能核**形状化**的声明（报文 ID / 服务名 / 端口 / 路径）。散文里的数字
（"每 2 秒发一次心跳"）核对不了 —— 那需要一个读者，不是一个正则。

### 3j. 重连：节奏按参考实现，蓝牙出向连接也会重试

上轮我实现会话重试时把间隔定成 5 秒——**那是编的**。这轮把参考实现的重连路径挖出来：

- 它的重连节奏是**每轮 20 秒**（日志原文 `Next-round RFCOMM connect timeout=20000ms;
  closing socket`，超时就关掉待用 socket 进入下一轮）；
- **Wi-Fi 断开 = 会话失败**：`ConnectionService.runWireless` 的 `lost` 处理里先关掉
  network/discovery/socket，再抛 `车机 Wi-Fi 已断开`，由外层重试接管；
- 它的**蓝牙出向连接本身就是会重试**的（一轮一轮地试）。

本仓库据此对齐：

- 会话重试间隔 5 秒 → **20 秒**（与参考实现同一节奏），仍最多 3 次；
- **新增蓝牙出向连接重试**：`connectAccessory` 失败后按 20 秒一轮重试，最多 3 次，
  失败则明确记录"只能等车机主动连过来（入向仍可用）"——此前是一次失败就放弃。

### 3i. 断言质量审计：测试本身也会骗人

新增 `scripts/audit-assertions.py`，检查**断言本身**能否失败：

- 恒真形态（`|| true`、`!false`、字面量与自身比较）；
- 只说了"某个计数不是负数"（计数永远不可能是负数，等于没断言）；
- 同一组里检查名重复（通常是后一条覆盖了前一条而不是补充）；
- 整组**没有任何否定断言** —— 对一个以"拒绝/忽略/不响应"为主要职责的协议实现来说，
  只测正面路径的组值得再看一眼。

**它先抓出了自己**：第一版把 `check(name, condition)` 的**第一个参数（名字）**当成条件，
于是恒真断言一条都测不出来，还报出"0 findings"这种假干净。因为它自带
`--selfcheck`（往测试目录里种一个已知有缺陷的组、跑一遍、要求全部被抓到），
这个 bug 才暴露出来。修好后它对真实测试树的结论是：

```text
499 checks over 20 suites, 0 finding(s)
```

即：没有恒真断言、没有重复检查名、每个组都至少有一条否定断言。这条结论现在可以信，
因为工具本身先被验证过了。

跑法：

```powershell
& _analysis\venv\Scripts\python.exe scripts\audit-assertions.py --selfcheck   # 先验工具
& _analysis\venv\Scripts\python.exe scripts\audit-assertions.py              # 再验测试
```

### 3h. 日志覆盖率审计：找出"写了但永远不执行"的分支

新增 `scripts/audit-log-coverage.py`：把源码里所有日志字面量与自测**实际打印过**的日志做差集，
并按"这条路径桌面测得到吗"分组。桌面测不到的（WiFi/蓝牙/投屏/无障碍/编码器）单独归档，
**剩下的就是"能测但从未执行"的分支** —— 那些正是最容易写错、也最容易被我误认为"已实现"的地方。

这个工具当轮就抓到三类问题：

1. **我自己的三条断言是空的**。`短密码被拒`、`不支持的安全类型被拒` 这几条走的是
   **未认证** 那条路，所以它们实际只证明了"未认证就拒绝"，**从未碰到它们声称在测的规则**。
   现在改成走认证后的路径，并加了"好配置确实被接受"的正对照 —— 否则拒绝可能是别的原因造成的。
2. **一处与参考实现的偏差**：端口字段参考实现要求**必须是 4 字节且落在 1..65535**，
   我写成了 `port != 0 && ...`，把 0 当作"车机没给端口"放过了。已按参考实现收紧（并测了 2 字节的端口）。
3. **六条从未被执行的分支**补上了测试：证书不是证书、证书消息里没有参数 0、
   描述符缺失的设备被跳过、读不了的描述符被拒、未知设备的报文不注入、正文没有长度时的告警。

跑法：

```powershell
# 语料 = 全部自测的输出（各套现在都会把自己的 app 日志一并打印）
powershell -ExecutionPolicy Bypass -File scripts\run-selftests.ps1
& _analysis\venv\Scripts\python.exe scripts\audit-log-coverage.py --corpus reports\selftest-all.log
```

这轮把审计的**输入**修好之后，这些数字才第一次可信。原来 `scripts/run-selftests.ps1` 只把**失败**套件的输出打到
控制台，**通过**的套件输出直接丢掉 —— 于是 `reports/selftest-all.log` 里没有各套的 app 日志，审计把 306 条
日志**全部**报成"从没执行过"。这不是审计算错了（它就是对给它的文件做子串搜索），是喂进去的语料一直是空的。
现在 runner 每跑一次都把全部输出写进 `reports/selftest-all.log`（控制台仍然只留失败和总计），审计自己也加了闸门：
语料里找不到 `---- app log ----` 这类套件标记就直接拒绝（退出码 2），而不是报一份"全都没测过"的假警报。

本轮实测（310 条日志、20 个套件、536 条断言；工具每次都打印当次的数字）：

- **154 条**在桌面根本到不了的路径上（Wi-Fi、蓝牙、投屏、无障碍、编码器），预期如此；
- **55 条**在桌面能到但没被跑到，全是**故障分支**：配对没应答、证书密钥类型不支持、事件通道开不起来、
  对端把通道关了、报文比描述符短之类。每条都能说出"要让它跑需要什么"，但为它们各写一个"对手故意乱来"的
  套件，收益不如先把真车日志拿回来；
- 也就是说：没有"没人知道它会不会跑"的分支了，每一条都有明确归属。

这轮的收获是那句老话的翻版：**审计通过不代表审计跑在对的输入上**。一个只读你给它的文件的检查器，
没法发现自己拿到的是一份空文件。

### 3g. 命令应答的状态码要"如实"

逐条核对参考实现处理车机命令的完整分支后，发现它对每个分支返回的是
**"这件事有没有真的做成"**，而我们原来一律回 200：

- `forceKeyFrame`：没有编码器时参考实现返回失败 → 我们现在同样回 **501**（并记
  `no encoder is running yet`）；
- `requestSiri`：参考实现在这里**打开麦克风**并派发语音键 → 我们没有麦克风通路，
  因此回 **501** 并明确记录，而不是假装完成（这样日志里能看出"用户按了语音键"）。

`requestUI` 的两种情形（带 url → 501 / 不带 url → 200）与 `changeModes` 的
`{status:0, params}` 早前已对齐；至此车机下发的六种命令（`iAPSendMessage`、
`hidSendReport`、`requestUI`、`changeModes`、`forceKeyFrame`、`requestSiri`）
**全部**与参考实现逐条对过，没有第七种会改变会话状态的类型。

### 3f. 对外广告（参考实现有、我们完全没有）

我们自己的分析文档早就把 "`_carplay-ctrl` 广告"列进"必须保留的 CarPlay 行为清单"，
但**代码里从来没实现过** —— 我们只**浏览**车机的 AirPlay 服务，从不**宣告**自己。

参考实现一边浏览、一边做这件事：回应 `_carplay-ctrl._tcp` 的查询
（日志原文 `control query answered peer=... unicast=...`），并按查询里的 unicast 位
决定回单播还是组播；同时周期性（2 秒）对外发广告。它还**先开 `ServerSocket`、
再广告那个端口**（`startInvitationListener`），因为广告出去的端口必须真的有人听。

本仓库现已照做：

- `CarplayControlListener`：先开一个监听端口并接受车机的连接，收到什么就**原样打六进制日志**
  （不猜协议）；
- `CarplayAdvertiser`：加入 `224.0.0.251:5353`、取组播锁（需要
  `CHANGE_WIFI_MULTICAST_STATE`，已加进 manifest），回应关于本机服务/实例/主机名的查询
  （名字大小写与结尾点都视为等价），并每 2 秒发一次广告；
- 端口 5353 被系统 NSD 占用时退回临时端口，并**明确记一条日志**说明车机可能忽略
  （mDNS 客户端常常校验源端口必须是 5353）。

这可能是"车机看不见手机"的另一半答案：之前我们只在蓝牙层宣告身份，Wi-Fi 层从没宣告过。

### 3e. 会话心跳（参考实现有、我们完全没有）

参考实现在会话存活期间有一个 `feedback` 任务：**每 2 秒 `POST /feedback`**（无正文）。
接收端有权把"不再回报"的会话当作已消失 —— 缺这条心跳的表现可能是
"刚开始正常、过一会儿就断"。我们原来一次都没发过，现已照做：RECORD 之后起线程，
每 2 秒一条，随会话结束停止，失败即记录并退出（不刷屏）。

它**故意不进日志**（`Request.quiet`）：每 2 秒两条日志会把有用的行全埋掉，
所以只在第一条记 `feedback keep-alive running (every 2s)`、之后每 15 条（约 30 秒）记一次计数。

### 3d. 停滞与重连（参考实现有、我们原来没有的两件事）

- **视频发送看门狗**。参考实现有个 `video-watchdog` 任务：20 秒没有发出画面就判
  `视频发送停滞超过 20 秒` 并终止会话。我们原来什么都没有 —— 车机若中途停止读数据，
  发送线程会**永远卡在 `write` 上**，不报错也不恢复，看起来会话还活着。
  现在同样是 20 秒阈值：超时就把隧道关掉（关 socket 才能让阻塞的 write 抛错），
  发送侧据此退出并记录。
- **会话重试**。参考实现在 provider 层有租约/重试；我们原来一次失败就结束。
  现在失败后最多重试 3 次、间隔 5 秒（**这个间隔是编的，3j 里已按参考实现改成 20 秒**），并用一个 generation 计数保证"Stop 之前排队的重试"
  不会打到下一次运行上；到达上限后明确记一条"不再重试"。

两处都只影响异常路径，正常流程的行为不变。

### 3c. 重复报文

车机重发同一条报文时不能重复动作，这一点已用测试**验证过**而不是靠读代码：

- 重复的 `0x1D01`（识别）**不会**再请求一次认证（`0xAA00` 只发一次）；
- 重复的 `0x4301`（Wi-Fi 配置）**不会**再启一次入网；
- 但配置**真的变了**（例如端口/地址不同）时会重新通知 —— 这是期望行为。

### 3b. 反复开关（生命周期）

Start/Stop 反复切换时，两处会留下残留，都已修（并加了日志，真车跑一次能看到它们执行）：

- **mDNS 的两条订阅只记得后一条**（`AirplayBootstrap`）。循环里注册了 `_airplay._tcp` 和
  `_raop._tcp` 两条发现订阅，但字段只存了后者，于是第一条在 Stop 之后仍在跑；下一次 Start
  又注册一遍，累积下去部分 ROM 会直接拒绝注册 —— 表现是"第二次连接发现不到车机"。
  现在记全部、全部停掉。
- **车机打开的链路没有关闭**（`CarplayBluetooth`）。之前只关监听 socket，
  而读线程阻塞在**已接受的那个 socket** 的 `read()` 上 —— `Thread.interrupt()` 唤不醒它，
  于是线程和 RFCOMM 链路一直留着，车机那边也仍认为链路是通的。
  现在把接受到的 socket 记下来，Stop 时逐个关闭（这样读线程才会被唤醒退出）。

两处都只影响运行在手机上时反复开关的行为，桌面自测覆盖不到（要到真机日志里看
`stopping N discovery subscription(s)` 和 `closing N open link(s) to the car` 这两行）。

## 4. 还没验证的地方（不粉饰）

1. **一次真车机都没跑过。** 上面的线格式来自对参考 APK 的静态分析加公开 AirPlay 规范。
   536 项自测只证明实现自洽，**不证明车机接受**。
2. **整套顺序按参考实现重排过，但没对过真车。** 早期版本把会话和流塞在一次 SETUP 里、
   还自己公告端口，那是错的：真流程是先 `SETUP /setup` 建会话，`GET /info`，
   开事件通道，再 `SETUP /` 只交 type 110 让车机分端口。
   `modesChanged` 的 body、`changeModes` 的处理规则与回复形状（`{status:0, params}`）、
   `requestUI` 的 501 语义、config 帧的 AVCC 载荷、以及每个请求的 `User-Agent: AirPlay/320.17`
   与 `Session` 头（含"回复不带该头时不清空会话"这条）都已按参考实现逐字段对齐。
   每一轮回头复查都会再挖出几处"看起来对、实际解不开"的东西，所以复查还在继续。
3. **音频依赖 MFiSAP。** 音频密钥来自 `/auth-setup` 的共享秘密；车机若没有这个端点
   （回 404），本仓库会协商好音频流但不发声。参考实现在那种情况下的做法也一样未定。
4. **视频封装只有 AES-CTR 那条路径。** 参考实现还有一条 ChaCha20-Poly1305 路径
   （AAD = 整个 128 字节头，偏移 0 = 明文长度+16）。本仓库走 AES-CTR，因为密钥就是
   `/auth-setup` 谈出来的那个；如果真车日志显示它要的是 ChaCha 那条，改一个分支即可。
5. **iAP2 链路帧已逐处核对并修正**（数据帧 control=64、`ack` 填最后收到的序号、收到载荷回纯应答，见 `docs/carplay-iap2-bootstrap.md` 5b 节）； 报文 id、参数 id、封装形状、流程顺序都有反编译依据，
   但"谁先发 `0x1D00`"与重传策略仍只能靠真车确认。
   本版两种发起方都兼容（先等 4 秒，车机不说话才自己发起），并且两个方向都打十六进制日志。
6. **Apple 身份不只是 SDP 记录。** 参考 APK 里还有 `libcarplay_eir_hook.so`，
   按硬编码 RVA 改 `libbluetooth_jni.so` 的 EIR/SDP，让系统蓝牙层真的用 iPhone 的
   Class-of-Device + EIR 广播。那一步需要 Shizuku/Dhizuku（shell 权限）注入。
   本仓库这版只做 App 层 SDP 记录，**车机可能因此压根看不到 iPhone** —— 最可能卡住的一步。
7. 参考 APK 依赖 Shizuku/Dhizuku + Xposed（`moe.shizuku.manager.permission.API_V23`、
   `XposedHelpers`）。严格讲"不 root 不额外修改"只对"无 root"成立：Shizuku 需要一次无线调试授权。
8. **车机若只支持有线 CarPlay**，手机就必须改 USB 设备描述符去冒充 Apple 设备，
   那是内核/驱动层的事，App 层做不到（需要 root 或改系统）。
   本方案成立的前提是车机支持**无线** CarPlay。
9. 触控的描述符解析按参考实现的规则写，但**tip 用的 usage 与参考实现不一致**
   （我们按标准用 0x42，参考实现写的是 0x51，见 6b 节），真车日志一看便知。
10. 触控是**实时**的（本轮改）：按下即派发一段 `willContinue` 的手势，之后每次车机报文
   继续一段，另有一个 100ms 的保活线程（`SEGMENT_MS=200 > FLUSH_INTERVAL_MS=100`），
   所以静止长按不会被判成抬手，滑动跟手。手势统一在主线程派发（车机报文来自事件线程）。
   落点坐标已按描述符的逻辑区间归一。唯一没在真机上验证的是各家 ROM 对
   `dispatchGesture` 继续段的行为。
11. 配对密钥派生里的 salt/info 串取自参考实现，车机若不接受会在 pair-verify 被拒。

## 5. 按价值排序的下一步

1. 真机跑一次，把第 3 节的日志发回来。iAP2 的每一步现在都有逐条日志，
   尤其是 `iAP2 <- 0x1D01 ... accessory-sends=[...]` 这一行，直接告诉我们车机打算怎么走。
2. 若车机看不到 iPhone：补 EIR/CoD 那一层（要 Shizuku），或让用户在车机端手动挑 `iPhone` 配对。
3. 若引导卡在某一级：按日志补该级报文（比如车机要求 `0x4E0C` 之外的身份项）。
4. 真车日志里确认隧道方向：车机什么时候发第一条 `iAPSendMessage`、之后 iAP2 还要谈什么。
5. 若 `SETUP /setup` 或流 SETUP 被拒：按返回码调 `/setup` 的字段（尤其 `osBuildVersion`
   这个兼容性开关）和流条目；`GET /info` 的回包会告诉我们车机认可的分辨率。
6. 触控的 tip usage（见上面第 9 条）按真车日志定夺。
7. 音频换成真实采集（现在发的是 440 Hz 正弦，用来证明封装正确）；麦克风那路
   （`audioType="speechRecognition"` + `DataStream-Input-Encryption-Key`）还没做。
