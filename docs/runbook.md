# 真车自查清单（一次运行 = 一次完整定位）

装好 APK 后按 `docs/carplay-sender.md` 第 2 节操作，然后**把日志整段复制出来**，
用仓库里的检查器读一遍：

```powershell
# 从手机取（需要 adb 与已连接的手机）
powershell -ExecutionPolicy Bypass -File scripts\run-selftests.ps1     # 先确认本地代码没坏
& _analysis\venv\Scripts\python.exe scripts\check-run-log.py --logcat

# 或者把日志存成文件再读
& _analysis\venv\Scripts\python.exe scripts\check-run-log.py --log run.txt
```

检查器输出三段：**走到了哪一步**、**下一步没看到的是什么**、**哪些行说明有降级**。
每一段里的模式都与源码逐字校验（`--verify-patterns`），所以它不会引用一条已经不存在的日志。

## 1. 流水线步骤（按顺序）

| 步骤 | 日志行开头 | 含义 |
| --- | --- | --- |
| service | `service created` | 前台服务起来了 |
| bluetooth | `SDP registered: CarPlay Probe` | CarPlay 身份已发布 |
| invited | `inviting the car to tcp/` | 广告里那个端口已经真的听起来 |
| advertising | `advertising ` | 正在对外宣告"本机是 CarPlay 控制端"（`_carplay-ctrl._tcp`，每 2 秒） |
| car-found-us | `the car connected to the invited port from` | **车机主动连过来了**（说明广告生效） |
| link | `RFCOMM` | 与车机的蓝牙链路存在 |
| link-open | `link open; waiting up to` | iAP2 链路正在打开（先等车机说话） |
| iap2-session | `control session` | 链路会话号已协商 |
| identify | `bootstrap -> 0x1D00 request identification` | 手机开始识别自己 |
| identified | `bootstrap <- 0x1D01 the car sends` | 车机回了能力表 —— **这一行最有用** |
| authenticate | `bootstrap -> 0xAA00 request authentication` | 请求认证 |
| certificate | `bootstrap <- 0xAA01 certificate bytes=` | 车机给出证书 |
| challenge | `bootstrap -> 0xAA02 challenge ` | 挑战发出 |
| signed | `bootstrap <- 0xAA03 signature valid=` | 车机证明了私钥（后面应是 `true`） |
| announced | `bootstrap announced as ` | 设备公告发出 |
| wifi-offer | `bootstrap Wi-Fi from 0x` | 车机把 Wi-Fi 凭据交过来了 |
| wifi-join | `Wi-Fi: joined ` | 手机已入网 |
| airplay-connect | `AirPlay connected to ` | AirPlay 连接建立 |
| auth-path / mfisap | `auth-setup unavailable (` / `MFiSAP certificate-bytes=` | 走的是哪条认证路 |
| session | `session established: ` | `SETUP /setup` 建会话成功 |
| info | `GET /info fields=` | 车机报了显示能力 |
| display | `the car's display ` | 从 `/info` 的 `displays` 里读出的屏幕（含车机自己的 `maxFPS` 上限） |
| event-channel | `event channel open` | 事件通道就绪 |
| modes | `modesChanged accepted; reply fields=` | 模式被接受 |
| screen-stream | `streams agreed: screen type 110` | 屏幕流谈好（端口在这里） |
| audio-formats | `the car announced PCM formats mask=` | 车机公告的 PCM 格式掩码（先报哪个格式由它决定） |
| audio-stream | `audio agreed: ` | 音频流谈好 |
| record | `RECORD accepted; the media path can start` | 会话开跑 |
| feedback | `feedback keep-alive running (every` | 会话心跳（每 2 秒 `POST /feedback`，参考实现同样如此） |
| tunnel | `iAP tunnel armed; waiting for the car` | 等车机第一条 iAP 报文 |
| encode | `encoding at ` | 编码尺寸/帧率/密度决定（对照手机屏幕看合不合理） |
| video-producer | `video path running` | 屏幕源起来了 |
| video-config | `video configuration sent from ` | 解码器配置发出（来源也会写明） |
| video-frames | `video tunnel sent ` | 画面在发给车机 |
| audio-frames | `audio path running: ` | 声音在发给车机 |
| hid-device | `HID device registered uuid=` | 车机交代了它的输入设备 |
| touch | `HID touch finger ` | 车机的触摸报文被正确解出 |
| touch-injected | `car touch` | 触点已注入手机 |

**怎么读**：检查器会给出 `furthest step seen` 与 `next step not seen`。
后者就是卡住的位置；对照上表看那一级需要什么，以及它前面一级的输出对不对。

## 2. 降级行（出现即说明某功能没了，但流程可能继续）

| 日志行 | 说明 |
| --- | --- |
| `auth-setup unavailable` | 车机没有 MFi 端点：屏幕会用配对密钥加密，而车机解不开 |
| `no MFiSAP key` | 同上，屏幕发出去车机也解不开（协议栈会继续跑完） |
| `no audio offer was accepted` / `no audio key from the handshake` | 本次没有声音 |
| `the car announced no audio formats` | 车机没说它收哪些 PCM 格式：后面的报价是按顺序试的（真车日志里这一行最关键） |
| `HID descriptor unsupported for` | 车机的输入设备描述符解不了：**触控不会生效** |
| `no descriptor registered for` | 报文来了但没有描述符：不注入（不猜） |
| `screen capture refused` | 车机看到的是测试图案，不是手机屏幕 |
| `car rejected` | 车机拒了某个请求，**同一行里有状态码** |
| `signature valid=false` | 车机没通过签名验证，引导在这里终止 |
| `the car closed the session` | 车机主动断开 |
| `out of range port` / `requires mutual authentication` | 车机给的配置被这一侧拒绝了 |
| `port 5353 is taken` | 系统 NSD 占着 5353：我们从别的端口发广告，车机可能忽略 |
| `no multicast lock` | 没有组播锁，熄屏后广告可能被过滤 |
| `control bytes from the car` | **车机在那个端口上说话了**：把这行十六进制发回来——那就是下一级协议 |
| `no encoder is running yet; answering 501` | 车机要关键帧时屏幕源还没起来（已如实回 501，参考实现同样以"请求是否真的执行"决定状态码） |
| `voice input, which is not implemented` | 车机请求语音输入，本侧没有麦克风通路（同样回 501 而不是假装成功） |
| `is not a touch screen` | 车机给了一个**不是触摸屏**的输入设备（方控/按钮），本侧还没解按键报文，已如实回 501 |
| `cannot decode the HID report as finger` | 报文的字节和车机自己给的描述符对不上（对着上面的十六进制看） |
| `which this side does not implement; answering 501` | 车机发来一个本侧没实现的命令类型（类型名在同一行）——参考实现对不认识的类型也是 501 |
| `change modes in a way this side does not handle` | 车机要求移交屏幕，但这条 `changeModes` 本侧处理不了（`transferType`/资源条数不对），已按参考实现回 501，屏幕归属不变 |
| `feedback keep-alive stopped` | 心跳失败：会话多半已经被车机断掉了 |
| `video send stalled for 20s` | 车机超过 20 秒没在读：隧道被关掉，发送线程才能退出（参考实现同样阈值，直接判会话失败） |
| `the outbound link did not come up; trying again in` | 蓝牙出向连接没建起来，正在按 20 秒一轮重试（参考实现同样节奏） |
| `the outbound link gave up after` | 出向连接三次都没建起来；此时只能等车机主动连过来（入向仍可用） |
| `retrying the AirPlay session in ...` | 会话失败，正在重建（最多 3 次，每次间隔 20 秒） |
| `; not retrying any more` | 连续失败到上限，这一侧不再重试 |

> 有一行会被**故意放过**：`the car refused PCM/...`。车机公告了格式时，**第一个**报价就该被
> 接受；被拒说明公告和我们理解的不一致，于是退路继续试剩下的候选（每次都记账），所以这种
> "被拒"不算故障，但值得对着 `audio-formats` 那一行看。检查器只对**不在预期内**的
> `car rejected` 报警（例如车机拒了 `SETUP`）。

## 3. 每一步需要什么（排障顺序）

1. **卡在 bluetooth**：车机根本没把这台手机当 iPhone（EIR/Class-of-Device 那一层，
   参考实现用原生 hook 做的，本仓库只做了 SDP 记录）。这一步卡住不意外，见
   `docs/carplay-sender.md` 第 4 节第 6 条。
2. **卡在 identified / signed**：车机的 iAP2 报文与我们的预期不同 ——
   `iAP2 <- 0x...` 那几行有完整的十六进制，直接能看出它想说什么。
3. **卡在 wifi-offer**：车机没下发 Wi-Fi。看 `bootstrap <- 0x4301 ...` 有没有出现、
   或它是不是要求了双向认证（会被拒）。
4. **卡在 session / screen-stream**：看 `<- 4xx/5xx` 的状态码；`GET /info fields=` 告诉我们
   车机认可的能力。
5. **卡在 video-config / video-frames**：看 `encoding at` 这一行的尺寸是否合理、
   `video configuration sent from` 的来源是 csd 还是关键帧。
6. **卡在 touch**：看 `HID device registered` 的字段数/手指数；若显示
   `HID descriptor unsupported`，把那一行里的描述符十六进制发回来即可。

## 4. 检查器自己的验证

检查器的每个模式都用 `--verify-patterns` 对着源码校验；此外它被喂过桌面自测
（`SelfTestClient`）产出的日志，并断言到 `touch-injected` 为止的步骤都能认出来：

```powershell
& _analysis\venv\Scripts\python.exe scripts\check-run-log.py --verify-patterns
& _analysis\venv\Scripts\python.exe scripts\check-run-log.py ^
    --log reports\selftest-client.log --expect record,tunnel,hid-device,touch,touch-injected
```
