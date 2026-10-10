# 架构

## 运行角色

开发板在车机侧扮演 CarPlay 发送端，在手机侧扮演 ICCOA Carlink 车机端。两端不能共享原始会话对象，桥接核心只交换统一媒体和事件模型。

```text
CarPlay USB/NCM <-> CarPlay 发送适配器 <-> BridgeSession <-> ICCOA Carlink 车机适配器 <-> Wi-Fi/BT
                                              |
                           MediaFrame / InputEvent / AudioPacket
```

## 核心状态

`BridgeSession` 的合法主路径：

```text
Idle
  -> CarplayEnumerating
  -> CarplayConnected
  -> CarlifeConnecting
  -> CarlifeConnected
  -> Active
```

任一已连接状态收到链路丢失事件后进入 `Reconnecting`。不可恢复错误进入 `Fault`，必须显式 `Reset`。严格拒绝乱序事件，避免半连接状态继续消费媒体。

注意：`BridgeState` / `SessionEvent` 里的 `Carlife*` 命名要统一成与来源无关的 `Source*`，属于独立的重命名提交；ICCOA 侧另有自己的边界状态机 `bridge-iccoa::IccoaSession`。

## 数据通道

- 视频：默认从来源侧 H.264 直接重封装到 CarPlay H.264，不改变 NAL 数据。分辨率、SPS/PPS 或时间戳不兼容时才允许进入转码路径。
- 输入：车机触控和按键统一转换为 `InputEvent`，再映射为来源侧输入命令。
- 音频：媒体、导航、电话、TTS、麦克风和系统音分立，禁止把通话音频无条件混入媒体通道。
- 控制：连接、电话状态、昼夜模式、GPS 和媒体元数据通过独立控制事件同步。

## 适配器边界

`bridge-core` 不包含 Linux 文件系统、USB 或网络调用。真实实现应新增以下适配器：

- `carplay-tx-adapter`：USB Gadget、iAP2、NCM、CarPlay 发送端。
- `iccoa-hu-adapter`（已落地为 `crates/bridge-iccoa`）：发现、ICCOA Carlink 车机端和媒体通道；线格式待规范或抓包补齐。
- `linux-platform`：UDC、configfs、Wi-Fi AP、BlueZ、watchdog 和升级。
- `media-platform`：DMA-BUF、V4L2/VAAPI 或零拷贝共享缓冲。

## 仿真传输

`bridge-core::wire` 的 `ZB01` 帧只用于本项目的测试台，带 magic、版本、通道、序号、长度和 FNV-1a 校验。它不代表 CarPlay、iAP2、NCM 或 CarLife 的真实线格式。真实适配器必须使用公开规范、获得许可的 SDK 或干净室行为结果。

## 失败策略

- USB 枚举失败：停止 CarLife 通道，记录 UDC 和 role-switch 状态。
- NCM 不通：不允许视频管线启动。
- CarLife 握手失败：保留 CarPlay 枚举，但显示明确的“无手机设备”状态。
- 视频时间戳倒退：丢弃当前帧并请求关键帧，不允许继续输出。
- 音频队列溢出：优先保留电话和导航，丢弃最旧的媒体包。
- 任何故障不得影响原车倒车影像、仪表、空调或驾驶辅助。
