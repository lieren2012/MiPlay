# ICCOA Carlink 手机侧协议笔记（静态分析得出）

## 结论

**ICCOA Carlink 的底座是 Miracast / Wi-Fi Display（WFD），ICCOA/OPPO 只是在它上面加了一组扩展参数。**

WFD 是**公开标准**（Wi-Fi Display Technical Specification），所以这套协议有据可依；需要逆向的只是扩展部分，
而扩展参数名已经全部从二进制里读出来了（见下）。

## 证据来源

- 设备：**OPPO PLP110**（ColorOS，Android 17），机主自有，`adb` 只读拉取
- 包：`com.oplus.ocar`（`/data/app/.../base.apk`）与 `com.heytap.opluscarlink`
  （`/product/app/CarLink/CarLink.apk`）
- 传播层在 `com.oplus.ocar` 的原生库里：**`libheycastcore.so`**（1.85 MB，投屏核心）
  与 `libECSocksServer.so`
- 抽取方式：`zipfile` 解包 + 字符串表过滤（`_analysis/oppo/libs.txt`、`_analysis/oppo/wfd-strings.txt`）
- **没有抓包、没有连盒子**，纯静态分析

关键证据原句（`libheycastcore.so`）：

```
Transport: RTP/AVP/%s;unicast;client_port=%d-%d;server_port=%d-%d
RTP/AVP/TCP;unicast %u %u mode=play
_ZN7android17WifiDisplaySource15setCastTypeModeERKNSt6__ndk112basic_stringIc...
Sink report iccoa_support_fusion: %s
Sink doesn't report UIBC capabitity
Sink doesn't report its aes key and iv
Sink doesn't report OSC AUDIO ENCRYPT / OSC ROTATES / osc_display_mode
```

`android::WifiDisplaySource` 就是 AOSP 的 WFD 发送端类，OPPO 在它上面做了扩展（`setCastTypeMode`、
`enableFusion`、`enableVehFusionResolutionUpto`）。

## 参数表

### WFD 标准参数（公开规范里有定义）

```
wfd_video_formats        wfd_audio_codecs         wfd_client_rtp_ports
wfd_content_protection   wfd_uibc_capability      wfd_video_latency
wfd_audio_latency        wfd_idr_request
```

### OPPO / ICCOA 扩展参数（逆向得出）

```
iccoa_support_fusion          Sink 是否支持融合桌面（车机与手机桌面融合）
iccoa_support_hevc            HEVC 能力协商
iccoaFusionResolutions        融合模式支持的分辨率集合

osc_display_mode              显示模式
osc_rotates_with_content      随内容旋转
osc_backstage_projection_full 后台全屏投屏
osc_is_relay                  是否为中继投屏
osc_audio_encrypt            音频加密开关
osc_audio_encrypt_aes_key    音频加密 AES 密钥
osc_audio_encrypt_rsa_key    用于包裹 AES 密钥的 RSA 公钥

wfd_car_display_mode          车机显示模式
wfd_car_vars                  车机侧变量
wfd_pc_display_mode           PC 显示模式
wfd_codec_type                编码类型
wfd_videosize_capability      视频尺寸能力
wfd_av_raw_packet             原始音视频包模式
```

另有若干 OPPO 私有属性（`oplus.wfd.video-format`、`oplus.wfd.video-resolution`、
`oplus-encode-usage-flag`、`persist.oplus.cast.*`、`com.oplus.cast.debug.fps`），属于实现细节而非线协议。

## 由此解释清楚的几件事

1. **发现方式不是 mDNS，而是 WFD Information Element**（WFD IE 承载在 Wi-Fi Direct 的
   probe request/response 里）。这解释了为什么：
   - 手机侧看不到任何 mDNS 服务类型的痕迹；
   - 第二台设备「连不上盒子的热点」——那是 **P2P 群组**（群主是车机/盒子），不是普通 AP；
   - 我们之前在 Android 侧推断的 "Wi-Fi Direct" 是正确方向。
2. **媒体通道**是 RTP/AVP，UDP 或 TCP 都支持（`mode=play`），端口在 RTSP 的 `Transport` 头里协商
   （`client_port=%d-%d;server_port=%d-%d`），不是固定端口。
3. **输入**走 WFD 的 **UIBC**（User Input Back Channel）——触控/按键回传。
4. **内容保护**可选：AES（`Sink doesn't report its aes key and iv`）。
5. 音频还有一层独立的可选加密（`osc_audio_encrypt*`）。

## 对实现的意义

| 部分 | 依据 | 可写程度 |
| --- | --- | --- |
| Wi-Fi Direct(P2P) 发现 + WFD IE | 公开规范 + 本笔记 | 可写，需真机确认 IE 细节 |
| RTSP M1–M7 会话 | 公开规范（WFD） | 可写，参数名已确认 |
| `iccoa_*` / `osc_*` 扩展参数 | 本笔记（逆向） | 可写骨架，语义需真机确认 |
| RTP 媒体与 UIBC 输入 | 公开规范 | 可写 |
| AES 内容保护 / 音频加密 | 逆向 + 规范 | 握手可写，密钥派生需真机验证 |

## 待确认（必须真机/盒子验证）

- P2P 群主是谁（手机还是车机端）以及发起顺序；
- WFD IE 里 ICCOA 具体用哪个子元素/位标识（`iccoa_support_fusion` 上报格式）；
- `osc_display_mode` / `iccoaFusionResolutions` 的取值编码；
- 音频加密的密钥派生细节；
- 分辨率/帧率的协商上限与实际取值。

## 边界

- 只分析机主自有设备上的系统应用，只读、不修改手机。
- `com.heytap.opluscarlink` / `com.oplus.ocar` 是专有软件；本仓库**只记录协议事实，不复制代码**。
- 抓包与盒子台架仍然是最后验证手段，但不是写代码的前提。
