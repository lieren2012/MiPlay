# 阶段 1：软件仿真验收

## 已实现范围

- 严格会话状态机，拒绝乱序事件并在链路丢失后进入恢复状态。
- H.264 零重封装检查，验证时间戳单调、序号连续和分辨率一致。
- 音频类型、采样率、声道和序号检查。
- 触控坐标和输入时间戳检查。
- 项目内部仿真帧的编码、解码、长度和校验检测。
- 30 分钟虚拟会话与连续 100 次重连测试。

## 未实现范围

- 真实 iAP2、NCM、AirPlay-CarPlay、MFi 验证流程。
- 真实 ICCOA Carlink Wi-Fi/BT 发现、鉴权、媒体和控制协议。
- Linux USB Gadget、Wi-Fi AP、BlueZ 和音频设备绑定。
- 真车 USB 枚举、视频显示、麦克风和方控。

## 自动命令

```powershell
cargo fmt --all -- --check
cargo check --workspace
cargo test --workspace
cargo run -p bridge-lab -- sim --seconds 1800 --fps 60 --report reports/phase1-soak.json
cargo run -p bridge-lab -- reconnect --cycles 100
```

若只在 Windows 上编译默认 MSVC 目标但缺少 `link.exe`，使用 Ubuntu 虚拟机，或安装 Visual Studio Build Tools 的 C++ 桌面组件。

## 通过条件

- 所有测试通过。
- 30 分钟虚拟会话 `108000/108000` 帧完成。
- 连续 100 次重连后最终状态为 `Active`。
- 报告标记为 `simulation-only`。
- 性能数据只用于估算，不直接外推到 Pi Zero 2 W。
