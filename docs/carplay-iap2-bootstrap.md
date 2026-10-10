# CarPlay 无线引导：从参考 APK 抽出的 iAP2 报文表

来源：`C:\Users\hz\Desktop\base.apk` 的静态反编译（`_analysis/src/io_github_iamr0s_carplay_protocol_*.java`）。
这里只记**协议事实**（报文 id、参数 id、封装形状、流程顺序），不含参考实现的代码。

链路：手机与车机的蓝牙 RFCOMM 上是 iAP2。

## 1. 线格式（与仓库里 `Iap2.java` 一致）

控制消息（CSM）：

```text
40 40 | length(u16, = 载荷+6) | messageId(u16) | 每个参数: length(u16, = 值长+4) | id(u16) | value
```

链路帧（LinkFrame）：

```text
FF | totalLength(u16) | control(u8) | sequence(u8) | ack(u16) | session(u8) | headerChecksum(u16) | payload | payloadChecksum(u8)
```

- control 位：`SYN=0x80`、`ACK=0x40`、`EAK=0x20`、`RST=0x10`；同步帧用 SYN，带应答的同步帧用 `SYN|ACK=0xC0`，数据帧带 `ACK=0x40`。
- 校验和：header 用 16 位和；payload 用 8 位补码和。
- 标识载荷（同步帧的 payload，13 字节）：

```text
01 | receiveWindow | maxReceivePacket(u16) | retransmissionMs(u16) | ackTimeoutMs(u16) | maxRetries | maxCumulativeAcks | [sessionId, 0x00, 0x02]
```

手机主动发起用：

```text
01 7F 08 00 07 D0 01 00 1E 01 01 00 02      -> 控制会话 1
```

车机主动发起、手机只应答时用：

```text
01 05 08 00 05 DC 00 49 1E 03 0A 00 02      -> 控制会话 10
```

会话号取三元组里后两字节为 `(0,2)` 的那一项的第一个字节。

## 2. 报文 id

手机 → 车机：

| id | 含义 | 参数 |
| --- | --- | --- |
| `0x1D00` | 请求识别（链路建立后第一条） | 无 |
| `0x1D02` | 接受识别 | 无 |
| `0xAA00` | 请求认证 | 无 |
| `0xAA02` | 挑战（challenge） | 0 = 挑战值（P-256 时 32 字节，RSA 时 20 字节） |
| `0xAA04` | 认证失败 | 无 |
| `0xAA05` | 认证成功 | 无 |
| `0x4E09` | 设备名 | 0 = UTF-8（NUL 结尾，参考实现用 `"CarPlay Android"`） |
| `0x4E0A` | 语言 | 0 = `"zh"` |
| `0x4E0C` | 设备 uuid | 0 = 文本（可选，车机不一定收） |
| `0x4E0D` | 传输标志 | 0 = `{0x01}` |
| `0x4E0E` | transport id | 0 = 文本 |
| `0x4300` | 无线可用（"modern" 车机才收） | 1 = 嵌套参数段 |
| `0x5001` | NowPlaying 更新 | 媒体/播放状态字段 |
| `0x5701` | 无线状态应答 | 0 = `{0x02}` |
| `0x5702` | 请求下发 Wi-Fi 配置 | 无 |

车机 → 手机：

| id | 含义 | 参数 |
| --- | --- | --- |
| `0x1D01` | 识别信息 | 6 = 车机**发送**的 id 列表（u16 对），7 = 车机**接收**的 id 列表 |
| `0x1D03` | 拒绝识别 | — |
| `0xAA01` | 证书链 | 0 = DER 的 X.509 链 |
| `0xAA03` | 挑战签名 | 0 = 签名 |
| `0xAA04` | 认证失败 | — |
| `0x4300` | 无线可用 | — |
| `0x4301` | 无线启动（**Wi-Fi 凭据在这里**） | 1 = 嵌套参数段，2 = 端口（4 字节大端），3 = receiver id（文本，可重复），4 = receiver 公钥（文本），8 = 双向认证标志 |
| `0x5700` | 索要无线状态 | — |
| `0x5703` | Wi-Fi 配置（扁平版） | 1 = SSID，2 = 密码，3 = 安全类型，4 = 信道 |
| `0x5000` / `0x5002` | 订阅/退订 NowPlaying | 0 = 媒体，1 = 播放状态 |

`0x4301` 的**嵌套参数段**（参数 1 的值本身是一条 id=0 的 CSM）里的字段：

| 字段 id | 含义 |
| --- | --- |
| 0 | SSID（UTF-8，1..32 字节） |
| 1 | 密码（UTF-8，≤64 字节） |
| 2 | 信道 |
| 3 | receiver 地址（可重复，最多 16 条，每条 ≤128 字符） |
| 4 | 安全类型：`0`=开放、`2`=WPA/WPA2、`3`=企业、`4`=WPA3；**WEP 不接受** |

端口必须是 1..65535；参数 8 首字节为 `1` 表示车机要求双向认证（参考实现直接放弃这一轮）。

## 3. 流程顺序

```text
手机  -> 0x1D00
车机  -> 0x1D01   (能力表；参考实现据此判断 modern = 车机发 0x4301 且收 0x4300)
手机  -> 0x1D02
手机  -> 0xAA00
车机  -> 0xAA01   证书链
手机  -> 0xAA02   挑战
车机  -> 0xAA03   用证书私钥对挑战签名
手机  -> 0xAA05
手机  -> 0x4E09 / 0x4E0A / 0x4E0C / 0x4E0E / 0x4E0D, [0x4300], 0x5702
车机  -> 0x4301 或 0x5703   Wi-Fi 凭据 + AirPlay endpoint
```

签名验证（参考实现的 `verifyChallenge`）：

- EC 密钥：`NONEwithECDSA`，直接对**挑战值本身**验证（挑战即摘要）；响应 64 字节时是裸 `(r,s)`，先包成 DER。
- RSA 密钥：`NONEwithRSA`，对 `DigestInfo 前缀 || 挑战` 验证；挑战长 20（SHA-1）或 32（SHA-256）。
- 参考实现明确标注 `trust-chain-verified=false / development-trust=true`，即**不校验 MFi 信任根**，只要求签名对得上。

## 4. 之后的 iAP2 走哪里

拿到 Wi-Fi 之后，参考实现把 iAP2 报文搬进 AirPlay 的 RTSP 连接，作为
`POST /command`，body 是 plist：`{type: "iAPSendMessage", params: {data: <裸 CSM 字节>}}`。
也就是蓝牙只负责引导，长连接之后换成 Wi-Fi 上的 RTSP 隧道。

## 5b. 链路帧的 control 位与 ack（逐处核对 `Iap2Probe` 得到）

| 用途 | control | sequence | ack | session | payload |
| --- | --- | --- | --- | --- | --- |
| 同步帧 | `128` SYN | 本机递增 | `0` | `0` | 13 字节标识载荷 |
| 应答同步帧 | `192` SYN+ACK | 本机递增 | `0` | `0` | 对方的标识载荷 |
| 数据帧 | `64` ACK | 本机递增 | **最后收到的序号** | 协商出的会话号 | 控制消息分片 |
| 纯应答 | `64` ACK | 本机递增 | 最后收到的序号 | `0` | 空 |

要点：

- 数据帧的 `control` 是 **64（ACK 位）**，不是 0。
- **`ack` 字段要填“最后收到的序号”**，不是 0。参考实现每收一帧就更新，并在数据帧
  和纯应答里都带上；一直填 0 等于告诉对端“我一帧都没收到”。
- 会话协商出来之后，数据帧的 `session` 字段才非零；纯应答的 `session` 仍是 0。
- 收到带载荷的帧之后回一条纯应答（`LinkFrame(64, seq, ack, 0, empty)`）。

## 5. 还没验证的

- 链路帧的 control 位组合与 ack 策略（参考实现里也用 `LinkFrame(64,…)` 承载数据帧，本仓库照做）；真车跑一次就能确认。
- 车机是否要求 `0x1D00` 必须由手机先发（两种发起方都做了兼容）。
- `0x4E0D`/`0x4E0E` 的语义按参考实现的用法推断，名字是自取的。
