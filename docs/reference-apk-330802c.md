# MiPlay 主参考 APK

MiPlay 的 Android 端开发以用户提供的 `base.apk(2).1` 为行为参考。这个样本与
`base.apk.1.1` 是同一份文件，SHA-256 为：

```text
E152D830B099A3986DE27432781968CDEAD4EA62CCB30FDA520C433DB9D76686
```

仓库只保存分析结果，不保存原始 APK、反编译产物或第三方签名。

## 已确认的身份

| 项目 | 结果 |
| --- | --- |
| applicationId | `com.baidu.carlife.xiaomi` |
| versionCode | `10007` |
| versionName | `7-330802c` |
| label | `CarWith & Carplay Bridge` |
| minSdk | `29` |
| targetSdk | `37` |
| 主 Application | `dev.carwith.bridge.BridgeApp` |
| 主 Activity | `dev.carwith.bridge.MainActivity` |

## 行为边界

该 APK 的主链路是：

```text
小米 CarWith / CarLife
        |
        | com.miui.carlink
        | com.xiaomi.ucar.carlife.CarlifeConnService
        v
CarLife mixing AIDL / Surface / 音频回调
        |
        v
CarPlay / AirPlay 会话输出
        |
        v
只支持有线 CarPlay 的车机
```

静态分析确认了以下入口和接口：

- 通过显式 `bindService` 绑定 `com.miui.carlink` 的
  `com.xiaomi.ucar.carlife.CarlifeConnService`。
- 对连接请求校验 `bindPackageName`、`bindClassName`、`manufacturer`、
  `usb_type` 和 `is_box`，并支持 `dev.carwith.bridge.CONNECT` / `STOP` 操作。
- `CastConfig` 通过 Binder 传递 `width`、`height`、`dpi` 和 Android `Surface`，
  说明视频内容是由 CarWith/CarLife 写入 Surface，再由桥接层转发。
- `ICarLifeCastManager` 提供 `prepareCast`、`setCastCallback`、
  `setSystemAudioCallback`、`invokeMethod` 和 `stopCast`。
- `ICarLifeCastCallback` 提供投屏准备、触控、按键、音频监听、音乐信息和会话
  生命周期回调。
- 音频接口包含 `IRemoteAudioListener.onReceiveAudioStream` 以及
  `ISystemAudioCallback.onAudioStreamReady` / `onAudioStateChange`。因此，
  “没有声音”不能简单归因于 APK 没有音频接口，仍需在真实 CarWith 和车机上确认
  能力协商、回调注册和 CarPlay 音频发送是否完整。
- CarPlay 能力表包含 `SCREEN`、`AUDIO`、`AUDIO_PCM`、`AUDIO_ALAC`、
  `AUDIO_AAC_LC`、`AUDIO_AES_128_MFI_SAP_V1`、`CARPLAY_CONTROL` 等能力。

## 开发映射

MiPlay 会按下面的边界重建实现：

1. `carwith-source`：实现 CarWith/CarLife 的连接、Surface 视频、PCM/编码音频、
   触控、按键和状态回调。
2. `bridge-core`：把来源侧数据统一成视频帧、音频包、输入事件和会话状态。
3. `carplay-output`：复用已验证的 CarPlay/iAP2/AirPlay 会话模型，把统一媒体和
   输入事件送到车机。
4. Android 权限和 Shizuku 适配：只在目标系统确实需要时启用，保持普通手机上的
   权限失败可诊断、可恢复。

## 开源参考关系

`113324561/iccoa-carplay-sink` 是公开的 MIT 许可项目。MiPlay 参考它在
CarPlay/iAP2/AirPlay 会话、蓝牙引导、媒体通道和输入回传方面的**原理与协议边界**。
它不是用户 APK 的源码，也不是 MiPlay 的直接底层；MiPlay 的主行为基线仍然是上面的
`base.apk(2).1`，两者的来源和角色已经在工程中分开记录。

参考项目：

<https://github.com/113324561/iccoa-carplay-sink>

## 当前限制

- 该文档来自静态分析，不能证明任意小米手机、任意车机都能连接。
- 原始 APK 没有作为公开仓库的二进制依赖提交。
- 当前仓库已有的 `android/iccoa-sink` 仍是协议实验模块，不能称为用户 APK 的完整
  升级版；后续 Android 版本必须先接入上面的 CarWith/CarLife source adapter，才能
  发布为 MiPlay 功能版本。
