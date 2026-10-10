# iccoa-sink —— ICCOA Carlink 车机端（接收端）APK

## 这是什么

跑在 **Android 设备**上的 App，扮演 **ICCOA Carlink 的车机端（接收端）**：

- 起一个 Wi-Fi Display 风格的 RTSP 服务（`tcp/7236`），按 M1–M7 与手机协商；
- 上报车机能力参数（`wfd_*` 标准参数 + `iccoa_*` / `osc_*` 扩展，见 `crates/bridge-iccoa/src/spec.rs`）；
- 收 RTP 视频（`udp/19000`），H.264 解包（单 NAL / STAP-A / FU-A）→ MediaCodec → SurfaceView；
- 尽力实现 UIBC 触控回传（格式未与真机核对，失败只记日志不影响视频）；
- UI 显示状态、本机地址与完整日志；同时用 NSD 注册 `_wfd._tcp` / `_carlink._tcp` 广告。

**它不做什么**：不向车机输出 CarPlay。CarPlay 输出要求设备对车机冒充 iPhone
（Apple USB 身份 `05AC:12A8` + iAP2 + NCM + AirPlay），需要改写 USB gadget 描述符，
普通未 root 的手机无法做到；参考 APK 是靠 Xposed/Dhizuku + 原生 hook 才实现的。

## 构建

```powershell
powershell -ExecutionPolicy Bypass -File android\build-iccoa-sink.ps1
```

用便携工具链（无 Gradle、无 Android Studio）：`tools\android\jdk-17.0.20.1+1`、
`tools\android\android-14`（build-tools）、`tools\android\android-36\android.jar`。
流程：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → 塞 `classes.dex` → `zipalign` → `apksigner`。
产物：`android\iccoa-sink-0.1.0.apk`。

## 验证状态

| 项 | 状态 |
| --- | --- |
| 协议自测（桌面 JDK，模拟手机走完 M1–M7 + 三种 RTP 打包） | ✅ 全部通过（`SelfTest.java`） |
| 构建 / 签名 / badging 自检 | ✅ |
| **真机运行** | ❌ **尚未在任何设备上跑过**（虚拟机验证被跳过） |

## 上车/上机测试要点

1. App 装在有屏幕的 Android 设备上（Android 7.0+，手机/平板/Android 车机盒子都行）。
2. 启动 → 授权（定位/附近设备/通知）→ 记下界面上的本机 IP 与端口。
3. 让投屏手机与它**在同一网络**：最可行的是**在跑 App 的设备上开热点**，投屏手机连该热点。
4. 在投屏手机的 Carlink 里发起连接。若手机端没有反应，说明它的发现只认 Wi-Fi Direct/WFD IE，
   需要再补 P2P 发现（可能要系统权限）。
5. 把 App 界面日志 + 手机端行为反馈回来，据此对齐发现机制。
