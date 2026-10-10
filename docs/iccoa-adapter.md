# ICCOA Carlink 车机端适配器（`bridge-iccoa`）

## 定位

原来的目标是「开发板在车机侧扮演 CarPlay 发送端，在手机侧扮演 CarLife 车机端」。
本次改造把**手机侧换成了 ICCOA Carlink**：

```text
25 款君越原车 CarPlay USB 口
        |
        v
开发板：扮演 iPhone / CarPlay 发送端            <-- bridge-core + 未来 carplay-tx-adapter
        |
        v
开发板：扮演 ICCOA Carlink 车机端（接收端）      <-- bridge-iccoa（本文件）
        |
        v
Android 手机：厂商自带的 ICCOA Carlink 发送端
```

选择的是「同机/同板接收」方向：手机用厂商自带的 Carlink 发送端投屏，开发板作为车机端
接收，再以 CarPlay 发给原车。不再需要 CarWith/CarLink 的私有 hook，也不再需要
Shizuku/Dhizuku 提权。

**当前交付的是边界和可测骨架，不是可上车运行的实现。** 真正决定能否连通的那部分
（Carlink 的组帧、握手、通道化）没有开源资料，必须以规范或干净室抓包为依据补齐，
见下面「还没做的部分」。

## 六个信号

内容源层对 CarPlay 侧只暴露 6 个信号，APK 分析与 `bridge-core` 的既有模型是一一对应的：

| 信号 | 方向 | `bridge-iccoa` | `bridge-core` |
| --- | --- | --- | --- |
| 视频 | 手机 → 板 | `DownstreamUnit::Video` | `MediaFrame`（H.264 零重封装） |
| 系统/媒体音频 | 手机 → 板 | `DownstreamUnit::Audio` | `AudioPacket` |
| 麦克风 | 手机 → 板 | `DownstreamUnit::Microphone` | `AudioPacket { kind: Microphone }` |
| 元数据 | 手机 → 板 | `DownstreamUnit::Metadata` | 未来的 NowPlaying 通道 |
| 触控/按键 | 板 → 手机 | `UpstreamCommand` | `InputEvent` |
| 会话生命周期 | 双向 | `IccoaSession` | `BridgeSession` |

## 模块

| 文件 | 内容 |
| --- | --- |
| `src/discovery.rs` | 广告身份 `IccoaAdvertisement`、传输方式 `IccoaTransport`、能力集 `IccoaCapabilities` |
| `src/session.rs` | 车机端会话状态机 `IccoaSession`（Advertising → PeerFound → Securing → Negotiating → Streaming） |
| `src/channel.rs` | 6 个信号的映射与校验、触控坐标缩放、PCM 边界 |
| `src/codec.rs` | 线格式接缝 `IccoaCodec` + 仅供实验台的 `LabCodec` |
| `src/spec.rs` | 协议事实：WFD 标准参数名 + ICCOA/OPPO 扩展参数名 + 「车机必须上报的能力」清单 |
| `src/wfd.rs` | WFD/RTSP 层：RTSP 请求响应编解码、`text/parameters` 块、RTP 传输描述、车机能力通告（含缺项检测）、M1–M7 流程 |
| `src/sim.rs` | 笔记本上的 soak / 重连harness |

### 关于「未验证字段」

`IccoaAdvertisement::unverified()` 把服务类型、实例名、主机名、端口全部留成
`capture-required`，`validate()` 会在这些字段被真值填上之前拒绝向手机广告。
这样「猜出来的配置」不会悄悄变成一份看起来能用的配置——沿用仓库里
`phase0-required` / `unknown` 的既有做法。

### 约束的来源

`channel.rs` 里的 PCM 边界不是编的，是参考 APK 中
`CarWithDirectContract` 对同一批信号的校验（见 `apk-analysis-carwith-carplay.md` 第 4.1、6 节）：

- 声道数 1 或 2；采样率 8000..=48000 Hz；16 bit 小端；单体负载 1..=16384 字节且按 `声道数 × 2` 对齐；
- 触控坐标按 `值 × 目标宽 / 源宽` 缩放并夹紧到 `目标-1`；
- 麦克风在源侧必须单声道；
- 视频时间戳严格递增、序号连续（由 `bridge-core::MediaBridge` 继续把关）。

## 还没做的部分（关键）

1. **没有符合规范的编解码器。** `LabCodec` 是自造的实验帧格式，只在实验台里跑通链路，
   和 Carlink 没有任何关系。真正的 `IccoaCodec` 实现要么来自联盟规范，要么来自干净室抓包。
2. **没有发现/配对实现。** `IccoaAdvertisement` 只是把身份字段建模出来并强制填真值，
   真正的 mDNS/BLE 广告方式、鉴权流程、密钥协商必须从抓包确认。
3. **没有按键码表。** `UpstreamCommand::Key` 里的 `code` 是桥内逻辑键值，到 Carlink 线格式的
   映射属于编解码器的职责，同样需要抓包。
4. **没有 H.265 通路。** `bridge-core` v1 只有 H.264，`to_media_frame` 对 HEVC 显式报错。

### 补齐这些需要的一次抓包

对手方用**已有的 ICCOA 转 CarPlay 盒子**：它跑的就是同一套车机端逻辑。完整方案见 `docs/iccoa-capture.md`，
配套两个只读脚本：

- `scripts/iccoa_recon.py`：手机侧侦察。判定谁是 AP、盒子 IP、手机正连向哪些端口。
- `scripts/analyze_iccoa_capture.py`：读 pcap，输出流列表、包长分布、每秒包数和首部 hex，并从 mDNS 里抽出广播的服务名。

两个脚本都带 `--selftest`，不需要真机即可验证解析逻辑。

目标产物是 `docs/iccoa-capture.md`：四组时序（发现 / 握手 / 媒体 / 输入），每组包含报文编号区间、时间戳、方向和两侧日志。
拿到之后，把每个通道的帧头、字段偏移、加密/校验方式写成缺陷单，再实现 `IccoaCodec`。
在拿到之前，这个 crate 的其余部分可以继续独立演进和测试。

## 验证

```powershell
cargo fmt --all -- --check
cargo check --workspace --all-targets
cargo clippy --workspace --all-targets
# Windows 上没有 MSVC 链接器时，本仓库自带便携版 MinGW（tools/mingw64，免安装）：
# $env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER = "C:\carplaytocarlife\tools\mingw64\bin\gcc.exe"
cargo test --workspace --target x86_64-pc-windows-gnu
cargo run -p bridge-lab -- iccoa --seconds 30 --fps 60 --cycles 100 --report reports/iccoa-soak.json
python scripts/iccoa_recon.py --selftest
python scripts/analyze_iccoa_capture.py --selftest
```

`bridge-lab iccoa` 会在实验台上跑「ICCOA 车机端 → 通道映射 → `bridge-core` 媒体校验」的
完整链路，并附带 100 次会话重连。它是**链路自检**，不是可用性证明。

> Windows 上如果只有 Rust MSVC 工具链、没有 Visual Studio C++ 链接器，`cargo test`
> 无法链接（`link.exe not found`），只能跑 `check`/`clippy`；完整测试需要在 Ubuntu 虚拟机
> 或装好 Build Tools 的机器上执行。

## 后续待办

1. **命名统一**：`bridge-core::SessionEvent` 里仍有 `CarlifeConnectRequested` /
   `CarlifeHandshakeCompleted`，`BridgeState` 里仍有 `CarlifeConnecting` /
   `CarlifeConnected`，`SessionStats` 里仍有 `carlife_connections`。它们应当改名成与来源无关的
   `Source*`。这一步会牵动 `config/*.json`（`carlife_version`、`wireless_carlife`）、
   `scripts/check_phase0.py`、`scripts/collect_phone_info.py` 与 README，因此单独一次提交更合适。
2. 把 `bridge-core` 的 `PhoneProfile` 换成 ICCOA Carlink 能力字段（`carlink_version`、
   `wireless_carlink`），并同步 phase-0 检查器。
3. 采集实现第 4 节要求的抓包，产出 `docs/iccoa-capture.md`。
4. 实现 `carplay-tx-adapter`（USB Gadget / iAP2 / NCM），与本适配器对接。
