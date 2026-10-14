# MiPlay

MiPlay is an open-source Android bridge upgrade project based on the behavior of
the supplied `base.apk(2).1` (`com.baidu.carlife.xiaomi`, `7-330802c`). Its
primary path is CarWith/CarLife input on a Xiaomi phone and CarPlay/AirPlay
output to a vehicle head unit that exposes wired CarPlay.

The reference APK is the behavioral baseline, not a binary dependency. The
static-analysis findings and the clean-room implementation boundary are in
[`docs/reference-apk-330802c.md`](docs/reference-apk-330802c.md).

`iccoa-carplay-sink` is only a public protocol and architecture reference for
the CarPlay/iAP2/AirPlay side. It is not the source of the supplied APK and
MiPlay is not a repackaged copy of that project.

## Build

```powershell
powershell -ExecutionPolicy Bypass -File android\build-iccoa-sink.ps1
```

The script uses the repository-local toolchain under `tools\` or the
`MIPLAY_JDK`, `MIPLAY_BUILD_TOOLS`, `MIPLAY_ANDROID_JAR`, and
`MIPLAY_BOUNCYCASTLE` environment variables.

---

## Open-source protocol reference

The project used as a principle and protocol reference is:

https://github.com/113324561/iccoa-carplay-sink

It is used to understand the CarPlay/iAP2/AirPlay session boundary and media
channel design. The supplied APK remains the primary behavior reference for the
CarWith/CarLife side. Neither the APK nor its decompiled output is repacked into
the build.

这是一个干净室、分阶段实施的 CarPlay/CarLife 互操作项目。

目标链路：

```text
25 款君越原车 CarPlay USB 口
        |
        v
Linux 开发板：模拟 iPhone / CarPlay 发送端
        |
        v
开发板 Wi-Fi/BT：提供 ICCOA Carlink 车机端
        |
        v
Android 手机：厂商自带 ICCOA Carlink 发送端
```

最终目标是桥接视频、音频、麦克风、触控、旋钮、方控和会话状态，同时不修改手机系统、车机固件或车辆 CAN。

## 手机端 APK（当前开发路线）

`android/iccoa-sink` 是当前保留的 CarPlay 输出实验模块。MiPlay 的功能版本还需要接入
参考 APK 对应的 CarWith/CarLife source adapter，不能把这个实验模块单独称为完整升级版。

- 构建：`powershell -ExecutionPolicy Bypass -File android\build-iccoa-sink.ps1`
- 产物：`android/MiPlay-0.2.2.apk`
- 更新检测：启动后读取公开的 `update.json`，发现新版本后提示并打开 APK 下载地址
- 可复现构建：同一份源码重复构建得到**同一个** APK（脚本把所有 zip 条目时间戳固定），并打印 SHA-256
- 详情、真机步骤、日志回传清单与**未验证项**：`docs/carplay-sender.md`
- 真车自查清单 + 日志检查器：`docs/runbook.md`、`scripts/check-run-log.py`
- 桌面自测一键跑：`scripts/run-selftests.ps1`
- 日志覆盖率审计（找"写了但永远不执行"的分支）：`scripts/audit-log-coverage.py`
- 断言质量审计（找恒真断言/重复检查/只有正面路径的组）：`scripts/audit-assertions.py`
- 文档一致性审计（文档里的报文 ID / 服务名 / 端口 / 文件路径在代码里有没有出处）：`scripts/audit-docs.py`
- 从参考 APK 抽出的 iAP2 报文表（无线引导、Wi-Fi 下发、MFi 挑战）：`docs/carplay-iap2-bootstrap.md`
- 从参考 APK 抽出的 AirPlay 投屏会话事实（顺序、`/setup` 字段、`/auth-setup` 密钥交换、流类型与加密、模式同意、请求头）：`docs/carplay-airplay-session.md`
- 当前主参考 APK 的行为基线：`docs/reference-apk-330802c.md`
- 历史 APK 分析记录：`docs/apk-analysis-carwith-carplay.md`

## 当前状态

当前仓库完成的是**阶段 1 的协议和媒体管线原型**：

- Rust 状态机、媒体零重封装路径、音频和输入路由。
- 手机侧已按 ICCOA Carlink 车机端重建边界（`crates/bridge-iccoa`），见 `docs/iccoa-adapter.md`。
- 仅供项目内部使用的仿真传输帧格式。
- 30 分钟虚拟会话、连续 100 次重连和协议错误单元测试。
- Linux USB Device/OTG 预检接口。
- 阶段 0～4 的检查表和硬件判定文档。

当前代码尚未把参考 APK 的 CarWith/CarLife source adapter 接入发布 APK，也没有完成真实 CarPlay 或 ICCOA Carlink 的真车验证。仿真通过只说明桥接状态和媒体管线可以跑通，不代表任意车机会识别 MiPlay。

## 快速验证

需要 Rust stable。先运行：

```powershell
cargo fmt --all -- --check
cargo check --workspace
cargo test --workspace
cargo run -p bridge-lab -- sim --seconds 30 --fps 60 --payload 4096
cargo run -p bridge-lab -- reconnect --cycles 100
cargo run -p bridge-lab -- iccoa --seconds 30 --fps 60 --cycles 100
```

执行完整 30 分钟虚拟验收并生成报告：

```powershell
cargo run -p bridge-lab -- sim --seconds 1800 --fps 60 --report reports/phase1-soak.json
```

Windows 如果只有 Rust MSVC 工具链但没有 Visual Studio C++ 链接器，可改用 Ubuntu 虚拟机，或安装 Visual Studio Build Tools 的“使用 C++ 的桌面开发”组件。当前笔记本没有 USB Device Controller，下面命令应明确失败：

```powershell
cargo run -p bridge-lab -- preflight
```

## 目录

- `crates/bridge-core`：会话、媒体、输入、音频、仿真和测试。
- `crates/bridge-iccoa`：ICCOA Carlink 车机端边界、通道映射、线格式接缝与实验台。
- `crates/bridge-linux`：Linux UDC/configfs 预检边界。
- `crates/bridge-lab`：笔记本仿真与重连测试 CLI。
- `config`：阶段 0 的车辆、手机和许可证配置模板。
- `scripts`：阶段 0 检查器、阶段 1 一键验证脚本，以及 ICCOA 侦察/探测/抓包分析脚本（`iccoa_recon.py`、`iccoa_box_probe.py`、`iccoa_phone_logs.py`、`analyze_iccoa_capture.py`、`usb_probe.ps1`）。
- `docs`：架构、阶段判定、硬件接线和产品化计划。

## 阶段门槛

1. **阶段 0**：补齐车辆、手机和许可证信息；运行 `scripts/check_phase0.py`。
2. **阶段 1**：软件仿真通过 30 分钟、100 次重连；不宣称实车兼容。
3. **阶段 2**：Pi Zero 2 W 或同级 UDC 板让君越真实枚举，并完成最小视频/触控闭环。
4. **阶段 3**：补齐导航、音乐、通话、麦克风、方控和断线恢复；手机侧改由 ICCOA Carlink 车机端接入。
5. **阶段 4**：根据 Pi Zero 2 W 实测决定最终板，完成便携盒、电源和 4 小时实车测试。

## 安全与法律边界

- 不在行车过程中调试；任何车内测试都必须在安全停车状态完成。
- 不修改车机固件，不连接 CAN，不绕过原车安全系统。
- 不直接复用没有明确许可证的 CatPlay 源码。详见 `docs/clean-room.md`。
- 本仓库当前不包含 Apple、Google、百度或其他第三方的专有 SDK 或固件。
- ICCOA Carlink 的线格式以实现规范或干净室抓包为依据；在拿到之前，仓库内不包含任何来自厂商实现的代码或常量。
