# CarPlay 投屏会话：从参考 APK 抽出的 RTSP 事实

来源：`base.apk` 静态反编译（`_analysis/src/io_github_iamr0s_carplay_airplay_*.java`）。
只记协议事实，不含参考实现代码。仓库里的对应实现：`AirplayMessages`、`AirplayClient`、`AirplayAuth`、`AirplayStream`。

## 1. 会话顺序（参考实现 `TestScreenSession` 的 phase 顺序）

```text
airplay-connect   连接车机 RTSP（默认 7000）
airplay-auth      认证：看车机 TXT 里的 features/homekit 位选路
                    有 HomeKit 位 -> PhonePairing（SRP pair-setup/pair-verify）
                    否则        -> MfiAuthSetup（POST /auth-setup，MFiSAP + AES）
                  （参考实现还允许 /auth-setup 返回 404/501 时继续）
airplay-setup     SETUP /setup                  会话本身
airplay-info      GET   /info                   车机显示能力
airplay-events    GET   /event（第二条连接）     车机从这里下发请求
airplay-modes     POST  /command   modesChanged 告知本机模式
airplay-screen-setup SETUP /  streams=[type 130] （可选隧道，被拒就跳过）
                     SETUP /  streams=[type 110] 屏幕流
airplay-record    RECORD /                      开跑
video             数据流加密发送
```

关键点：**端口是车机在 SETUP 响应里分配的**，不是发送端自己公告的。
发送端只交一个 `streamConnectionID`，车机回 `dataPort`。

## 2. `SETUP /setup` 的 body（14 个字段）

| 字段 | 参考实现的值 |
| --- | --- |
| `deviceID` | transportId |
| `macAddress` | transportId（与 deviceID 相同） |
| `name` | `CarPlay Android` |
| `model` | `Android17,1` |
| `osBuildVersion` | `21A329` —— 注释明确写 "protocol-compatibility-version (not actual Android build)"，并说明 **>=13A1 会让车机选 audio AAD timestamp+SSRC** |
| `osVersion` | `17.0` |
| `osName` | `Android` |
| `sessionUUID` | 随机 UUID 大写 |
| `sourceVersion` | `320.17` |
| `timingPort` | 本机 UDP timing 端口 |
| `timingProtocol` | `NTP` |
| `isScreenMirroringSession` | false |
| `statsCollectionEnabled` | false |
| `features` | 空数组 |

认证成功后把 `{et, ekey, eiv}` **并进同一个字典**（`setupFields()` 直接 putAll）。

## 3. `POST /auth-setup`（MFiSAP，屏幕密钥的来源）

请求：`Content-Type: application/octet-stream`，body = `0x01 || X25519(phone, 32)`。

响应（大端 ByteBuffer，解析完不允许有剩余字节）：

```text
[0..32)          车机的 X25519 公钥
int32 长度 + 数据  X.509 证书链（DER）
int32 长度 + 数据  用共享密钥加密的签名
```

推导：

```text
shared  = X25519(phone_seed, car_public)
AES-KEY = SHA-1("AES-KEY" || shared)[0..16]
AES-IV  = SHA-1("AES-IV"  || shared)[0..16]
wrapping = AES/CTR/NoPadding（解密方向），key=AES-KEY, iv=AES-IV
signature = wrapping.update(加密字段)          // 这个密文就是签名
digest    = SHA-256(car_public || phone_public)，证书链 >640 字节时改用 SHA-1
校验      = 用叶子证书公钥验 signature 对 digest（EC 用 NONEwithECDSA，裸 64 字节要转 DER）
```

参考实现明确标注 `trust-chain-verified=false; development-trust=true`：**不校验 MFi 信任根**，只要求签名对得上。

密钥封装（放进 `/setup`）：

```text
baseKey = 随机 16 字节
baseIv  = 随机 16 字节
ekey    = wrapping.update(baseKey)   // 同一个 CTR 流，紧接签名之后继续，16 字节
eiv     = baseIv                     // 明文
et      = 16
```

日志原文：`MFiSAP SETUP et=16 ekey-bytes=16 eiv-bytes=16 wrapping-offset=<n>; CTR-continuation=true`
—— `CTR-continuation` 就是上面那个"接着签名继续"。

## 4. 屏幕数据流

**类型**：屏幕流 `type=110`；`type=130` 是可选隧道（参考实现日志写 "type130=optional unconfirmed-on-hangsheng"）。

**流条目**：
```text
{ type: 110, streamConnectionID: <id>, latencyMs: 100 }
```

**加密**（`MfiSecurity.screenCipher`）：

```text
key(n) = SHA-512("AirPlayStreamKey" + n || baseKey)[0..16]
iv(n)  = SHA-512("AirPlayStreamIV"  + n || baseIv )[0..16]
AES/CTR/NoPadding，密钥与 IV 分别来自 baseKey 与 baseIv
整个会话一个连续 keystream（不按帧重置）
```

注意：**key 用 baseKey、iv 用 baseIv**，两个不同的秘密 —— 只用 baseKey 推两个会解不开。

**帧封装**：128 字节头，偏移 0 = 密文长度（LE int32），偏移 8 = 时间戳（LE int64），正文从 128 开始。

## 4b. 视频帧的两种封装（`VideoPackets`）

参考实现里有三个不同的 128 字节头构造，屏幕流用前两个：

**开场 config 帧**（`config(body, width, height)`）——未加密：

```text
偏移 0   int32 LE  载荷长度
偏移 4   byte      1                    （版本）
偏移 6   byte      4                    （flags，参考实现日志：flags=4 view-area=zero）
偏移 16  float LE  宽
偏移 20  float LE  高
偏移 128 起       **AVCDecoderConfigurationRecord**（不是裸 SPS/PPS）
```

参考实现是先 `ingestConfig` 把编码器 csd 里的 SPS/PPS 分出来，再
`VideoPackets.avcc(sps, pps)` 拼出标准 avcC 记录（日志原文
`Video codec configuration sent sps-bytes=... pps-bytes=...`）：

```text
01 <sps[1]> <sps[2]> <sps[3]> FF E1  u16 len(SPS)  SPS  01  u16 len(PPS)  PPS
^configVersion=1   ^profile/compat/level    ^numSPS=1              ^numPPS=1
                   lengthSizeMinusOne=3
```

参数集**不带起始码**（所以 `sps[0]` 就是 NAL 头 0x67），长度**大端 u16**。
校验：SPS 长度 4..65535 且 NAL type=7，PPS 长度 1..65535 且 NAL type=8。

> 把裸 Annex-B 的 SPS/PPS 当载荷发出去，看起来几乎对，但车机解不出来 —— 又是一处"有流、黑屏"。

日志原文 `VIDEO config dimensions=<w>x<h> flags=4 view-area=zero; view-area-semantics=pending-validation`。
没有这一帧，车机解码器拿不到参数集 —— 这是"有数据但黑屏"最典型的原因。

**每帧**，两条路径二选一：

| 路径 | 构造 | 头 | 载荷 |
| --- | --- | --- | --- |
| AES-CTR | `aesFrame(body, cipher, ts)` | 偏移0 = 密文长度；偏移8 = 时间戳 | 128 起，AES-CTR 直接加密，无 MAC |
| ChaCha20-Poly1305 | `frame(body, key, counter, ts)` | 偏移0 = 明文长度+16；偏移8 = 时间戳 | 128 起，密钥 `key`、nonce 由 counter 派生、**AAD = 整个 128 字节头** |

参考实现按"有没有 ChaCha key / 有没有 MFi AES cipher"二选一（日志里会打 `ChaCha` 或 `AES-CTR`）。
本仓库走 AES-CTR 那条，因为密钥正是 `/auth-setup` 谈出来的（第 3 节）。

**时间戳是 NTP，不是微秒**：

```text
v11 = unixMillis
ntp = ((v11 / 1000 + 2208988800) << 32) | ((v11 % 1000) * 2^32 / 1000)
```

即高 32 位是"1900 起的秒"，低 32 位是小数部分，写进偏移 8 的小端 64 位。
写成微秒计数器会让车机的时间轴差一个半小时。

**参数集提取**：`nals(bytes)` 按 3/4 字节起始码切 NAL；`avcc(sps, pps)` 还能拼出标准
AVCDecoderConfigurationRecord（`01 <profile> <compat> <level> FF E1 |u16 len|SPS| 01 |u16 len|PPS|`），
本仓库用不到，但记录在此。

## 4c. 音频流

音频是**另起一次 `SETUP /`**，fields：

```text
type               = 96                    （媒体音频；麦克风是另一路）
audioType          = "media"               （麦克风那路是 "speechRecognition"）
audioFormat        = <PcmAudioFormat.bit>
streamConnectionID = <id>
audioLatencyMs     = 100
spf                = rate / 200            （每帧采样数，即 200 帧/秒、5ms 一帧）
sr                 = rate
ct                 = 1                     （压缩类型，参考实现写 1）
```

**选哪个格式：参考实现读车机自己公告的掩码，不猜**（`AudioTonePackets.select`）：

```text
候选（按顺序）: (32768,48000,2) (2048,44100,2) (16384,48000,1) (1024,44100,1)

从它拿到的 stream 描述里筛出 type == 96 且 audioType == "media" 的条目，
把每条的 audioOutputFormats 按位“或”成 mask，再按上面的顺序取**第一个**
bit 落在 mask 里的候选 —— 只发这一条 SETUP。

mask 里一个都没有 → 直接抛错：「车机没有公告可用的 type96/media PCM 格式；不猜测格式」。
```

`bit` 是格式位而不是位深（32768/16384/2048/1024 与 48k/44.1k、单/双声道配对）。
车机回包里要有 `type` / `dataPort` / `streamConnectionID`，参考实现会校验回显的
`streamConnectionID` 与请求一致，不一致就报"车机音频 streamConnectionID 不一致"。

本仓库照此但留了退路：**车机公告允许的那个格式排在第一位**，其余候选跟在后面；
车机什么都没公告时按原顺序试。（公告过期或我们读错字段时不至于完全没声音，
代价只是多一条被拒的 SETUP —— 每次拒绝都会打进日志。）两种形状都打日志：
`the car announced PCM formats mask=0x...`（有公告）或
`the car announced no audio formats; offering the four PCM formats in order`（没有）。

**传输是 UDP**，包格式（`AudioTonePackets.packet`）：

```text
12 字节头（大端）: 0x80 | payloadType(1B) | sequence(2B) | timestamp(4B) | ssrc(4B, =0)
载荷: ChaCha20-Poly1305，32 字节 key
AAD: 头部的 4..12 字节（timestamp + ssrc）
尾部: nonce 的 4..12 字节（8 字节）
```

`nonce(counter)` 是 12 字节：前 4 字节为 0，**counter 小端**写在 4..12。
尾部带的就是这 8 字节的小端计数器 —— 字节序写反的话车机每个包都验不过。

## 4d. 每条流的 ChaCha 密钥（音频，以及 video 的 ChaCha 路径）

`DataStream` 密钥由 `/auth-setup` 的 X25519 共享秘密派生：

```text
outputKey(n) = HKDF-SHA512(secret = shared, salt = "DataStream-Salt" + n,
                           info = "DataStream-Output-Encryption-Key")   → 32 字节
inputKey(n)  = 同上，info = "DataStream-Input-Encryption-Key"
```

n 是该流的 `streamConnectionID`。HKDF 的具体构造（`AirCrypto.hkdf`）是
`PRK = HMAC-SHA512(key=salt, msg=secret)`，再 `HMAC-SHA512(key=PRK, msg=info || 0x01)` 取 32 字节 ——
注意 salt 当 HMAC 的 key、secret 当消息，与 RFC 5869 的常见写法相反。

**因此：没有 `/auth-setup` 就没有共享秘密，也就没有音频密钥**（视频那边同理只能用
AES-CTR 那套 baseKey/baseIv）。本仓库在拿不到密钥时选择不发音频，而不是发车机解不开的包。

## 5. 事件通道

`GET /event` 开第二条 RTSP 连接，之后车机在这条连接上下发请求，发送端逐条回 200
（参考实现 `event.reply(event.read())`）。参考实现从这条路径里解析 `TouchReports` / `KeyReports`，
即**触控和按键走这里**，不是另开 HID 控制端口。

## 6. Wi-Fi 之后：iAP2 走 AirPlay 隧道

蓝牙只负责引导。会话建好之后，iAP2 搬到 AirPlay 连接上：

**发（手机 → 车机）**：`POST /command`，plist body

```text
{ type: "iAPSendMessage", params: { data: <裸 iAP2 CSM 字节，含 40 40 头> } }
```

**收（车机 → 手机）**：车机在 `/event` 那条连接上发 `POST /command`，同样信封，
`params.data` 就是裸 CSM 字节，喂回 `WirelessBootstrap.receive`。

**切换时机**（参考实现日志原文）：

- RECORD 之后 arm 隧道，**等车机先发第一条 `iAPSendMessage`**：
  `Runtime command transport armed; waiting for peer iAPSendMessage after RECORD; Bluetooth retained`
- 收到第一条时：`Runtime transport selected=AirPlay-command; first peer iAPSendMessage received`
  然后才把 bootstrap 的收发切到隧道；**蓝牙保持在线做退路**。

**车机下发的命令类型**（`invokeSuspend$command` 的 switch）：

| type | 参考实现的行为 |
| --- | --- |
| `iAPSendMessage` | `params.data` → 隧道输入 → bootstrap |
| `hidSendReport` | HID 报文 → `TouchReports.parse` / `KeyReports.parse`（触控与按键） |
| `requestUI` | 有处理分支 |
| `forceKeyFrame` | 向编码器请求 IDR |
| `requestSiri` | 有处理分支 |
| `changeModes` | 能处理 → `{status:0, params:<新状态>}`；处理不了 → **501**（细节见 6d） |
| 其它 | 回 501 并打印字段 |

**状态码的含义**：参考实现里每个分支返回的是"这件事有没有真的做成" ——
`forceKeyFrame` 在没有编码器时返回失败、`changeModes` 返回带 `status` 的快照、
`requestSiri` 返回"语音键有没有派发出去"。本仓库照此：**做不成就回 501**，
而不是一律回 200 假装完成（例如车机要关键帧但我们还没编码器、或车机请求语音输入但我们没有麦克风通路）。

日志格式：`AirPlay incoming command type=<type> fields=<...>`。

### 6a. 参考实现还有第二条隧道：`type 130` 的 runtime 流（本仓库没有）

`/command` 信封不是参考实现唯一的路。它先试着用 **SETUP 一条 `type = 130` 的流**
来承载 iAP2（日志 `Runtime command transport armed` / `Runtime type130 ...`）：

```text
SETUP / { streams: [ { type: 130, streamConnectionID: <id>, seed: <同一个 id>,
                       uuid: "E9459FD0-BCAD-4C45-820F-1E72447EF2F2" } ] }
```

- 车机给它分配端口后，参考实现在那条 socket 上跑 `RtspConnection.encrypt(key,
  "DataStream-Salt<n>", "DataStream-Input-Encryption-Key", "DataStream-Output-Encryption-Key")`，
  然后把 iAP2 链路的收发直接跑在上面（`tunnelJob$1` 收、`tunnelJob$2` 用 `WirelessBootstrap` 跑协议）。
- 它是**可选**的：被拒（或 profile 判定不该试）就退回 `/command` 信封：
  `Optional runtime type130 rejected status=...; continuing screen110; Bluetooth retained`；
  某些机型上参考实现干脆跳过：`Runtime type130 skipped: vendor compatibility profile; SETUP rejects
  types outside 100..111 and may tear down session; runtime=AirPlay-command`。
- 需要 `/auth-setup` 的 `DataStream` 共享秘密（legacy AES 路径上跳过：
  `Runtime type130 skipped on legacy AES; no DataStream shared secret`）。

**本仓库没有实现这条流**（只用 `/command` 信封）。如果真车上 iAP2 迟迟不动、
而日志里 `<- 200` 之后没有 `iAPSendMessage`，下一步就该把它补上 —— 上面几个字段名、
密钥标签、以及"被拒即退回"的策略都有出处了。

### 6b. 触控：车机把它的 HID 描述符交给我们

触控报文不是固定 6 字节，**描述符是车机在 `requestUI` 里给的**：

```text
POST /command  { type: "requestUI",
                 hidDevices: [ { uuid, name, hidDescriptor: <原始 HID report descriptor>,
                                 displayUUID? } ] }
```

参考实现对每个设备做两件事（日志原文）：

```text
HID device fields=<...>
HID touch registered device=<uuid> layout=<fields> output=<w>x<h>
HID key selector registered device=<uuid> name=<...>
HID descriptor unsupported device=<uuid> ...; raw descriptor retained
```

之后每个报文：

```text
POST /command  { type: "hidSendReport", uuid: "<设备>", hidReport: <字节> }
```

用该 uuid 注册的解码器解。回码不是"收到就算数"：

| 情况 | 参考实现 | 日志原文 |
| --- | --- | --- |
| 解出触摸点（**包括抬起**，`tip=false` 的那种） | 200 | `TOUCH device=... action=up/down/move ...` |
| `uuid` 缺失 | 501（抛错） | `HID command missing uuid` |
| `hidReport` 不是字节 | 501（抛错） | `HID command missing binary hidReport` |
| 该 uuid 没有触摸/按键解码器 | **501** | `HID unsupported device=<uuid>; no matching touch/key descriptor; response=501` |
| 报文按描述符解不出来 | **501** | `HID touch decode failed device=<uuid>; response=501; video retained` |

也就是：**只有真的解出东西才回 200**。本仓库照此实现（解出触摸点 → 200；没注册描述符、
描述符不是触摸屏、解不出来 → 501，各自留一条日志）。按键路径（`KeyReports`）本仓库还没有，
方控/按钮的报文会得到明确的 501 而不是被静默吞掉。

另外：**参考实现不硬编码任何 usage**。`tip`（0x42）、`contact id`（0x51）、坐标位宽这些
全都来自车机自己的描述符 —— 它把描述符编译成解码器（`TouchReports.Companion.parse`），
再用解码器解每一份报文。所以"用 0x42 还是 0x51"这种问题在参考实现里根本不存在，
本仓库同样是描述符驱动。

**描述符怎么解**（`TouchReports.Companion.parse`）：走标准 HID 短项
（`bType` = 0 Main / 1 Global / 2 Local），只认：

- Global：Usage Page(0)、Logical Min(1)、Logical Max(2)、Report Size(7)、Report Count(9)；
- Local：Usage(0)、Usage Min(1)、Usage Max(2)；
- Main：Input(8)，要求 **Data + 可变 + 绝对**（常数项跳过），
  且局部 usage 个数必须等于 report count，否则"Only absolute variable fields supported"；
- 有符号坐标直接拒绝（"Signed coordinates unsupported"），长项（0xFE）拒绝。

每个字段记 `(page, usage, bitOffset, bitSize, logicalMin, logicalMax)`，
**按位取值**：字段第 i 位 = 第 `(offset+i)/8` 字节的第 `(offset+i)%8` 位（小端、LSB 在前），
再 `(value-min)*(像素-1)/(max-min)` 映射到像素。

用的 usage：

| 含义 | page / usage |
| --- | --- |
| X | 1 / 0x30 |
| Y | 1 / 0x31 |
| contact identifier | 13 / 0x51 |
| tip switch | 13 / 0x51（参考实现里与 contact 同 usage，**可疑**） |

本仓库按标准用 **13 / 0x42**（tip switch）取 tip；若真车日志显示 tip 一直判成"按下"
（或一直抬起），把这一个常量改回 0x51 试即可 —— 就一处。

**本仓库的实现**：`HidDescriptor` 解析描述符（上表规则），
`HidDescriptor.value` 按位取字段，`decodeTouch(report, finger)` 出一根手指的
`(contactId, tip, x, y)`（x/y 归一到 0..4095，交给无障碍注入那一层）。
解析不了的描述符只记日志不注入 —— 不猜。

### 6c. 按下到抬起是怎么变成手势的（本仓库实现，非参考事实）

车机按自己的描述符发 `hidSendReport`，本仓库把它变成 Android 手势：

```text
tip=1 且此前没有手势  -> 派发一段 willContinue 的手势（SEGMENT_MS=200ms），起一个保活线程
tip=1 后续报文        -> 若距上次继续已过 FLUSH_INTERVAL_MS(100ms)，continueStroke 一段
                         否则只更新当前位置（保活线程到期也会继续）
tip=0                -> 若是"没移动且很快"则按 tap 派发；否则最后一次 continueStroke(willContinue=false)
```

保活线程的意义：车机在手指静止时可能不发报文，而**继续段必须在前一段到期前发出**，
否则系统会把手势判成抬手——长按就废了。所以 `FLUSH_INTERVAL_MS` 必须小于 `SEGMENT_MS`
（有一条自测专门盯着这个不变式）。

手势统一在主线程派发：车机报文来自事件通道线程，而手势必须按构造顺序发出。

### 6d. 模式与屏幕归属（`SessionModes`）

会话建立后手机要发一条 `POST /command`，body 就是**模式快照**（不是猜的，字段如下）：

```text
{ "type": "modesChanged",
  "params": {
    "resources": [
      { "resourceID": 1, "entity": <screen>, "permanentEntity": <permanentScreen> },
      { "resourceID": 2, "entity": <audio>,  "permanentEntity": <permanentAudio>  }
    ],
    "appStates": [
      { "appStateID": 1, "entity": <speech?1:0>, "speechMode": <speech?2:-1> },
      { "appStateID": 2, "entity": 0 },
      { "appStateID": 3, "entity": 0 }
    ]
  } }
```

- `entity`：**1 = 手机占用该资源，2 = 车机占用**。
- 参考实现的初值：`screen = permanentScreen = 1`（手机占用屏幕）、
  `audio = permanentAudio = 2`（车机占用音频）、`speech = false`。
  也就是说**默认就把屏幕算在自己头上**——车机什么都不问也能投屏。
- `speechMode = -1` 是**负数**：binary plist 没有有符号整数类型，
  参考实现（dd-plist）用 8 字节补码表示，本仓库也照此实现（否则 -1 会变成 255）。

**车机的 `changeModes` 是"屏幕归属移交"**，不是可选功能：

```text
POST /command { "type": "changeModes",
                "params": { "resources": [ { "resourceID": 1, "transferType": N } ] } }
```

`transferType`：`1` = 车机拿走屏幕（并把永久归属也改成车机）、
`2` = 手机拿回屏幕（永久）、`3` = 车机**临时**拿走、`4` = 回到永久归属者。

处理规则（参考实现 `SessionModes.change`）：只认 `resourceID = 1`，
且必须**恰好一条** transfer；`params.appStates` 非空则整条命令不处理。
处理完的回复形状是 `{ "status": 0, "params": <新状态的 params> }` —— 注意**不是**把
`modesChanged` 那条命令原样回过去（参考实现 `commandResponse` 取的正是快照里的 `params`，
再加一个 `status`）。日志原文 `Accessory screen changeModes response status=0 state=...`。

> 这一条很关键：如果车机要求 `transferType = 2` 而这边回了空 200 或 501，
> 屏幕归属不会变更，表现就是"链路全通、车机不显示"。

**能处理的和处理不了的，答案不一样**（`RtspConnection.reply` + `commandResponse` + `command`）：

| 车机发来的 | 参考实现的回答 |
| --- | --- |
| 能处理的 `changeModes` | `{ "status": 0, "params": <新状态的 params> }`（`commandResponse` 有返回值 → 200） |
| 处理不了的 `changeModes`（`params` 缺失、`appStates` 非空、资源不是恰好一条、`transferType` 不在 1..4） | **501 Not Implemented**（`commandResponse` 返回 null → `command` 布尔处理器也返回 false → 501） |
| 其它不认识的 type | 501（日志 `AirPlay command unsupported type=...; response=501`） |

参考实现这条路径上只有 **200 和 501** 两种状态码（`RtspConnection.reply` 里写死的），
没有 400。本仓库原来对"处理不了的 changeModes"回 400，已改成 501。

### 6e. `requestUI` 的回复

车机可以带 `url` 请求一个 UI：参考实现对**自己不认识的 url 回 501**
（日志 `Phone requestUI URL not implemented url=...; response=501`），
但同一个分支里**照样把 `hidDevices` 登记好**。也就是说"UI 服务不了"和"输入设备收下了"
是两件事，不能因为回了 501 就跳过描述符解析。

本仓库照此实现：先登记 HID 设备，再对带 url 的请求回 501（并把 url 打进日志）。

### 6f. 每个请求的头（`RtspConnection.request`）

参考实现把所有请求都拼成：

```text
<method> <uri> RTSP/1.0\r\n
CSeq: <n>\r\n
User-Agent: AirPlay/320.17\r\n            ← 每个请求都带（与 sourceVersion 同一个串）
Session: <id>\r\n                          ← 一旦有会话就带
Content-Type: ...\r\nContent-Length: ...\r\n\r\n<body>
```

而且会话的取用规则很关键（`RtspConnection` 应答处理）：

```text
if (headers.get("session") != null) this.session = substringBefore(";");
```

即**回复里没有 `Session` 头时保持原值，绝不清空**。本仓库早期版本写成
"没有就置空"，于是车机只要有一条应答不回该头，后续所有请求就都不带 `Session` 了 —— 
这类问题在真车上表现为"前面几步正常，从某一步开始车机不理你"。

### 6g. 编码尺寸与帧率由谁决定（`VideoTestOptions`）

**编码尺寸来自手机自己的屏幕**，不是车机 `/info` 报的尺寸：

```text
select(displayWidth, displayHeight, carAnnouncedFps) ->
  Triple( max(((scale * displayWidth ) / 100 / 2) * 2, 128),
          max(((scale * displayHeight) / 100 / 2) * 2, 128),
          selectedFps(carAnnouncedFps) )
```

- `scale` ∈ {50, 75, 100}，**默认 100**（即手机屏幕原尺寸），先整除 100、再向下取偶、下限 128。
- `selectedFps(car) = min(本机偏好, coerceIn(car, 1, 60))` —— **只能被车机压低**。
  参考实现允许的帧率是 {15, 30, 60}。

拿车机面板尺寸当编码尺寸会让宽高比和手机屏幕对不上（拉到变形或加黑边），
所以本仓库改用手机屏幕尺寸（默认 100%）并按上式取偶、限下限。

**帧率的字段名这轮定死了**（从参考实现 `TestScreenSession$run$2` 读出来的，不是猜的）：

```text
/info -> displays[ i ] , 取 type == 0 的那一项（没有 type 0 就用第一个能读的）
         widthPixels  / heightPixels   车机面板像素尺寸
         maxFPS                        车机自己的帧率上限；字段不在时按 **15** 算
```

参考实现读取处：`widthPixels` / `heightPixels` 缺失直接抛错（`Missing display width`），
`maxFPS` 不是数字时取 15。`displays` 整个不存在时才退回平铺字段。
本仓库现在按同一顺序读，并把**用的是哪种形状**打进日志：

```text
the car's display 1920x720 maxFPS=30 (displays[1].type=0)
the car's display 1920x720 maxFPS=15 (flat /info fields, no displays list)
the car's /info names no display size; the phone screen and its scale decide the encode size
```

（此前本仓库只在顶层找 `maxFps`，大小写也不对 —— 真车按参考实现那样把 `maxFPS` 放在
`displays` 里时，这个上限会被整条丢掉。）

### 6h. 解析健壮性与 csd（本仓库实现）

**RTSP 读取**（`AirplayClient.readMessageFrom`）：参考实现在读应答时设了三道闸，
本仓库补齐其中两道并放宽一道：

- **header 块上限**（参考实现 `RTSP header exceeded limit`）→ 我们 64 KiB；
- **拒绝 chunked**（参考实现 `Chunked RTSP unsupported`）→ 没有 Content-Length 就无从
  知道正文边界，猜下去会让整个流错位，所以直接抛错而不是继续读；
- 应答协议前缀：参考实现同时接受 `RTSP/` 和 `HTTP/`，我们原来只认 `RTSP/` —— 已放宽。

第三道 "Duplicate RTSP header" 我们**故意没跟**：取第一个同名字段比抛错更宽容，
而能跟参考实现配对成功的车机本来也不会发重复头。

**解码器配置（csd）**：之前只从关键帧的前导 NAL 里取 SPS/PPS。若某机型编码器**不在帧内
带参数集**，config 帧就永远发不出去 —— 表现是"这台手机黑屏、那台正常"。现在
`SurfaceEncoder` 同时收 `MediaFormat` 的 `csd-0/csd-1` 和带 `CODEC_CONFIG` 标志的输出缓冲，
发送端**优先用编码器自己的 csd**，取不到才回落到关键帧，并在日志里写明来源。

### 6i. `pair-setup` / `pair-verify` 是 TLV8，不是 plist

这两个端点（HomeKit 配对，AirPlay 直接复用）**不收发 plist**：body 是 TLV 项，
`Content-Type: application/pairing+tlv8`，并且带 `X-Apple-HKP: 2`（`/pair-verify` 还带
`X-Apple-PD: 1`）。TLV 项是「1 字节类型 + 1 字节长度 + 值」，**超过 255 字节的值要拆成
多个同类型项**，收端再拼回去 —— 参考实现的 `Tlv.encode/decode` 就是这两件事。

类型号（HomeKit 的，参考实现逐项对应）：

| 号 | 含义 | 号 | 含义 |
| --- | --- | --- | --- |
| 0 | method（1 = PairSetup，3 = PairVerify） | 6 | **state**（每一步都有，回包必须是下一步） |
| 1 | identifier（配对 id，UTF-8 文本） | 7 | error（非 0 就是拒绝，要读出来） |
| 2 | salt | 10 | signature（Ed25519，64 字节） |
| 3 | publicKey | 5 | encryptedData（封起来的 TLV） |
| 4 | proof（SRP 的 M1/M2，64 字节） | | |

流程（参考实现 `PhonePairing`，本仓库现在逐字对应）：

```text
pair-setup  1: {6:1, 0:1}                -> {6:2, 2:salt, 3:B(384)}
pair-setup  3: {6:3, 3:A(384), 4:M1(64)} -> {6:4, 4:M2(64)}
pair-setup  5: {6:5, 5:seal(TLV{1:id, 3:ltpk, 10:sig})}
                                        -> {6:6, 5:seal(TLV{1:accId, 3:accLtpk, 10:accSig})}
pair-verify 1: {6:1, 3:xpub(32)}         -> {6:2, 3:accPub(32), 5:seal(TLV{1:accId, 10:sig})}
pair-verify 3: {6:3, 5:seal(TLV{1:id, 10:sig})} -> {6:4}
```

四个封装的 nonce 各不相同，都是「4 个 0 字节 + 4 字节 ASCII 标签」的 12 字节：

```text
pair-setup  控制器->配件: "PS-Msg05"      配件->控制器: "PS-Msg06"
pair-verify 配件->控制器: "PV-Msg02"      控制器->配件: "PV-Msg03"
```

（用同一个 nonce 双向收发，对着自己的假配件能跑通、对着真车必然认证失败。）

签名覆盖的transcript也要对：

- pair-setup 里双方各签 `signInfo || pairingId || ltpk`（signInfo = HKDF(session,
  `Pair-Setup-Controller/Accessory-Sign-Salt/Info`)）；
- pair-verify 里配件签 `accPub || accId || controllerPub`，控制器签
  `controllerPub || controllerId || accPub` —— **三个字段，缺一不可**。

### 6j. pair-verify 之后，参考实现会加密整条会话连接（本仓库还没有）

`RtspConnection.encrypt(shared)` 用 pair-verify 的共享秘密派生：

```text
readKey  = HKDF(shared, "Control-Salt", "Control-Read-Encryption-Key")
writeKey = HKDF(shared, "Control-Salt", "Control-Write-Encryption-Key")
```

之后所有 RTSP 请求/应答都走这层（HAP 的帧格式）。**本仓库没有实现这一步** ——
配对成功后会明确记一行 `the control channel is NOT encrypted: ...`，好让真车日志一眼看出
"配对过了、后面却被车机挂断"是不是卡在这里。

## 7. 还没验证的

- `modesChanged` 的 body 字段（参考实现发的是自己的 mode 快照，字段集未确认）。
- `/info` 里 `displays[].{widthPixels,heightPixels,maxFPS}` 已确认（见 6g）；**其余**能力字段名
  仍只看日志（本仓库把整个字段表打印出来）。
- 事件通道上触控报文的具体信封（本仓库实现为：plist 里任何 6 字节 data 字段按 HID 触摸解码，并在日志里打全）。
- type 130 隧道的确切用途（被拒即跳过）。
- 车机公告 PCM 格式的 `audioOutputFormats` **具体出现在哪一条回包**里（`/info` 的 `streams`、
  还是 `SETUP` 的 `streams`）：本仓库两处都读，真车日志会定名。
- 会话连接的 Control-Salt 加密（见 6j，本仓库未实现）。
- `osBuildVersion` 的兼容性阈值（参考实现注释只说 >=13A1）。
