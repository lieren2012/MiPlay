# ICCOA Carlink 抓包采集方案

## 前提

> 想让盒子在没有车的情况下开工（用 DiPlay 当 CarPlay 主机），见 `docs/iccoa-bench-diplay.md`。

- 对手方是**你自己那台 ICCOA 转 CarPlay 盒子**：它跑的就是 ICCOA Carlink 车机端，
  与我们 `crates/bridge-iccoa` 要扮的角色完全一致。
- 全部操作在**安全停车、拉手刹**状态完成；不要边开边插拔或抓包。
- 只针对自己的设备。抓包、读日志、行为观察都在 `docs/clean-room.md` 的「可接受输入」范围内。

**抓包不是为了抄实现，而是为了知道线格式长什么样。** 没有规范、没有车机，只有这台盒子，
那么「一次完整投屏的报文 + 两侧日志」就是唯一的事实来源。

## 本项目的具体盒子：车连易 CPC200-U2H IE

已确认（来自机主）：

- 盒子自身是 Wi-Fi 热点，手机以客户端身份接入；
- 盒子自带一个 Web 管理地址。

待验证的假设（**不要当结论**，全部交给探测脚本去证实或推翻）：

- 这类盒子多为 Linux 小系统，Web 管理页由 `boa` 之类内嵌 HTTP 服务提供；
- 固件版本、热点 SSID 与 PSK 可以在 Web 页里读到；
- 可能残留 `telnet` / `adb` 调试口。

探测：

```powershell
python scripts/iccoa_box_probe.py --from-recon reports/iccoa-recon.json
```

它做三件事：探测固定端口表（含 22/23/5555）、抓取 Web 页的 Server/标题/链接/版本号线索、
把页面存到 `reports/box-web/` 供你用浏览器查看。报告 `reports/iccoa-box-probe.md` 末尾会直接
给出「由探测结果决定的采集路径」。两点约束：

- Web 页如果要密码，用 `--user/--password` 传入你已经知道的口令；脚本**不做口令爆破**；
- 只读 Web 页和它的 HTML/JS 属于行为观察；**不要下载、解包或再分发盒子固件**。

## 怎么跑：机器分工与顺序

两个脚本都在**电脑**上运行，但第 1 步的数据来自**手机**：

| 谁 | 在这两步里干什么 |
| --- | --- |
| 手机 | 连着**盒子的热点**（这就是投屏链路），同时用 **USB 线连着电脑**并打开 USB 调试 |
| 电脑 | 跑两个脚本；第 2 步时电脑还要能连到盒子（两种接法见下） |
| 盒子 | 提供热点和 Web 管理页；第 1 步通过手机看到它的端口，第 2 步直接访问它 |

两步都只读：不改手机、不改盒子配置。

### 第 1 步：手机侧侦察（必须 USB 连电脑）

1. 电脑上先确认 adb 可用。本仓库已自带（`tools/` 已在 `.gitignore` 里）：

   ```powershell
   tools\platform-tools\adb.exe version
   ```

2. 手机上开 USB 调试：设置 → 关于手机 → 连点「版本号」7 次 → 回「开发者选项」→ 打开 **USB 调试**
   （小米 / HyperOS 还要打开「USB 调试（安全设置）」，可能要求登录小米账号）。
3. USB 线连电脑，USB 用途选「传输文件」。手机上弹「是否允许 USB 调试」时勾「一律允许」并确定。
4. 电脑上确认连上了：

   ```powershell
   tools\platform-tools\adb.exe devices
   ```

   看到 `<序列号>  device` 就对了；显示 `unauthorized` 说明手机上的授权弹窗还没点确认。
5. 手机连上盒子热点，**并开始投屏**——没投屏就没有连接，第 6 步的端口列表会是空的。
6. 跑侦察：

   ```powershell
   python scripts/iccoa_recon.py --adb tools\platform-tools\adb.exe --report reports/iccoa-recon.md
   ```

7. 报告里重点看三行：**角色判断**、**网关（= 盒子 IP）**、**指向盒子的连接（端口列表）**。
   如果角色判断是 `phone-likely-ap`（手机在当热点），采集方案要换，先告诉我。

### 第 2 步：盒子探测（**不要**去连盒子热点）

实测结论：这类盒子的热点**只接受一个客户端**。手机投屏时占着它，第二台设备去连要么被拒绝，
要么把手机挤下线、投屏直接中断。所以第 2 步改成从手机侧读。

**2a. 用手机浏览器读盒子 Web 页（首选，零成本）**

手机本来就在盒子的网络里，直接用手机浏览器打开 `http://<第 1 步报告里的网关>`（例如 `http://192.168.43.1`）。
重点读这几样：

- 页面标题、固件版本、设备型号；
- **WLAN / 热点设置里的 SSID 与密码（PSK）** —— 监听模式解密必须用到；
- 有没有「升级 / 日志 / 调试」页，以及**网络模式**设置项（如果盒子能切成 STA 模式去连你家路由器，
  后面抓包会轻松很多，见下面 C 的备选）；
- 页面上任何 `telnet` / `adb` / `8080` / 调试 之类的字眼。

**2b. 端口探测：只有能从手机侧跑才有意义**

`iccoa_box_probe.py` 需要有设备在盒子网段内。手机没 root 时先跳过这一步。
如果第 1 步报告里「采集能力」显示手机已 root，可以在手机上用 Termux 跑同一个脚本（它只用标准库）。

### 2c. 把盒子插到电脑上：先读它的 USB 身份（成本最低，建议先试）
**实测结果（本机，2026-10-10）：** 盒子枚举成 `WPD  05AC:12A8  MTP USB 设备`，自报名字就是 `iPhone`，
CompatibleIds 为 `USB\Class_06&SubClass_01&Prot_01`。结论：

- **没有 ADB、没有串口、没有 RNDIS/NCM 网卡** —— USB 侧拿不到 shell。唯一残留可能是「MTP 浏览」，
  但本机上它没有作为便携设备出现在「此电脑」里（PTP-only），读不到文件；
- 盒子对主机**完整冒充了一台 iPhone**（VID 05AC / PID 12A8 / BusReportedDeviceDesc=iPhone），
  这正是它在车机上被识别为 CarPlay 的来源；
- 插在电脑上时**它没有建热点**（`netsh wlan show networks` 扫描里没有新 SSID），
  说明它要等与主机完成 CarPlay/iAP2 识别后才会拉起无线投影，插在电脑上它一直停在等待状态。

所以 USB 抓包这条路**关闭**，回到「手机日志 + 监听模式」。


盒子对外的物理接口就是 USB，所以插到电脑上时 Windows 会把它枚举出来，**枚举结果直接决定后面
有没有捷径**：

```powershell
# 插之前
powershell -ExecutionPolicy Bypass -File scripts\usb_probe.ps1 -Mode baseline
# 把盒子插到电脑 USB 口，等 5~10 秒
powershell -ExecutionPolicy Bypass -File scripts\usb_probe.ps1 -Mode diff
```

脚本只读 PnP 元数据（VID/PID、设备类、驱动、HardwareIds），不装驱动、不发自定义 USB 请求、不改任何东西。

| 枚举成什么 | 意味着什么 | 下一步 |
| --- | --- | --- |
| Android / ADB 接口（VID `18D1`，或 HardwareIds 里有 `ADB` / `WinUsb`） | 盒子带 adb | `tools\platform-tools\adb.exe devices`，通了就 `adb shell` —— **最好的情况** |
| 串口（Class=Ports、CDC、UsbSerialPort） | 可能有控制台 | 用 115200 读横幅，只读，不要敲命令 |
| RNDIS / NCM / ECM 网卡 | 盒子和电脑成一个小局域网 | `ipconfig /all` 看新网卡地址，再 `python scripts\iccoa_box_probe.py --host <对端地址>` |
| Apple VID `05AC` | 这就是它给车机看的 iPhone 身份 | USB 没有捷径，回监听模式那条路 |
| 什么都不像 | 私有协议设备 | 同上 |

两点要注意：

- 差集里偶尔会多出或少掉一个无关设备（USB 设备会休眠），按 VID:PID 和名称判断是不是盒子。
- 盒子插在电脑上时就**不在车里**了。它是否仍会启动热点、是否仍接受手机的 ICCOA 会话，需要实测：
  插着电脑 + 手机连盒子热点，看手机端投屏能不能起。起得来的话，只要盒子有 shell，一台设备就能
  抓完整会话；起不来的话，就得用「在盒子上开抓 → 拔下来插回车里跑一次会话 → 再插回电脑取文件」
  的顺序。后者需要在盒子上跑一次 tcpdump 并把文件写到它的存储里（不刷机、不改固件，用完删除即可）。

### 2d. 手机日志：不需要 root，最容易拿到信息

`adb logcat` 在绝大多数机型上不需要 root 就能读，而厂商投屏服务往往会打印大量本不该打印的东西：
发现方式、选中的传输、端口、协商的编码与分辨率、错误路径。无线抓不到时，这是最便宜的事实来源。

```powershell
python scripts/iccoa_phone_logs.py --adb tools\platform-tools\adb.exe --seconds 45
```

流程：清空日志缓冲 → 给你 45 秒去点开投屏 → 自动 dump 并按关键字过滤（carlink / carlife / iccoa /
carplay / p2p / mdns / h264 / gatt …）→ 去重后写入 `reports/iccoa-phone-logs.md`，全量日志在
`reports/phone-logcat.txt`。命中行里的**端口号、传输方式、协商参数、错误信息**就是补齐 `IccoaCodec` 的第一手线索。

**不要**为了跑这个脚本把电脑连到盒子热点上 —— 那会打断投屏。
## 第一步：侦察（先跑这个）

手机投屏到盒子时，在电脑上跑：

```powershell
python scripts/iccoa_recon.py --report reports/iccoa-recon.md
```

脚本只读 `adb shell` 本来就能读的状态（`cmd wifi status`、`ip addr/route`、`/proc/net/tcp|udp`、
`pm list packages`），不提权、不改手机。它回答三件事：

1. **谁是 AP**：看手机连的 SSID / BSSID / 频率（换算成信道）。
   如果 SSID 是盒子热点，那盒子就是 AP，手机是客户端。
2. **盒子 IP**：通常是默认网关。
3. **手机正在连谁**：`/proc/net/tcp|udp` 里指向网关的连接，就是控制/媒体端口的第一手线索。

自检（不需要手机）：`python scripts/iccoa_recon.py --selftest`。

## 第二步：抓包（按侵入性从低到高，能走通就停）

### A. 盒子有 shell —— 首选

这类盒子很多是 Android 或 Linux，常见入口：`adb connect <盒子IP>:5555`、`telnet <盒子IP>`、
Web 页面里的调试入口、或者板子上的 UART。**只要能进去，就在盒子上抓**，这是唯一能同时拿到
两端字节的方案：

```sh
tcpdump -i any -s 0 -w /tmp/iccoa.pcap
# 抓完把文件取回：
adb pull /tmp/iccoa.pcap    # 或 scp / nc
```

同时保存盒子自己的日志（`logcat`、`dmesg`、`/var/log/*`）：盒子的日志里通常直接写着协议阶段名，
可以用来给报文分组。

> 允许：读日志、跑 tcpdump、看 Web 页面的 HTML/JS 行为。
> 不允许：解包它们的固件、反编译二进制、提取私钥/证书、把它们的代码搬进本仓库。

### B. 手机侧 —— 永远可用

```powershell
adb shell cmd wifi status                 # 记录 SSID/BSSID/信道/RSSI
adb logcat -c
adb logcat -v time -b all > reports\phone-logcat.txt     # 期间完成一次完整投屏
adb shell dumpsys bluetooth_manager > reports\phone-bt.txt
```

蓝牙侧另外开一个 **HCI snoop log**（开发者选项里打开），一次会话后取回
`/data/misc/bluetooth/logs/btsnoop_hci.log`（部分机型要 root，或直接从 `adb bugreport` 里取）。
它记录配对/BLE 广播阶段，是「谁先发起、用什么服务 UUID」的关键证据。

### C. 监听模式（不需要连盒子热点，也不需要盒子有 shell）

监听模式抓的是空口，**不需要**成为盒子热点的客户端，所以单客户端限制不影响它。前提是：
有一块支持监听模式的网卡（Windows 内置网卡基本不行，用 Linux + 支持监听模式的 USB 网卡），
以及从 2a 拿到的 PSK。关联握手要靠手机侧配合：抓包开始后，把手机的 Wi-Fi 关掉再打开，
强制它重新关联，握手就抓到了。

用一台支持监听模式的 Linux 笔记本，在**手机还没连上盒子之前**开始抓：

```sh
sudo airmon-ng start wlan0
sudo tcpdump -i wlan0mon -s 0 -w iccoa-air.pcap
```

要点：

- 信道必须是第一步侦察到的那个（2.4G 常见 1/6/11，5G 见频率换算）。
- 必须抓到**关联握手**，之后 Wireshark 才能用 PSK 解密（Edit → Preferences → Protocols →
  IEEE 802.11 → Decryption keys → wpa-pwd）。
- PSK 通常在盒子说明书、机身贴纸或它的 Web 配置页上；这类盒子常用固定默认值。

⚠️ **期望管理**：如果第 1 步的接口列表/连接表显示这是 **Wi-Fi Direct(P2P)** 群组，那么即使抓到了空口，
P2P 群组密钥是群主协商出来的，旁观者通常**无法解密**，能拿到的只有帧长、时序、包数这类元数据
（外加未加密的 P2P action 帧与服务发现帧）。遇到这种情况先告诉我，再决定值不值得买监听网卡。

### D. 把盒子改成 STA 模式（如果 2a 发现有这个设置）

如果盒子的 Web 页允许它作为客户端去连你家路由器，那么手机、盒子、电脑就都在同一个局域网里，
这时电脑可以直接 `tcpdump`，连监听模式网卡都不需要。这条只有在盒子支持时才有。

### 不要做的事

- 不要把电脑（或第二台手机）连到盒子热点上：挤掉手机就前功尽弃；
- 不要为了抓包去改盒子的固件、刷机或解包固件。
## 第三步：读包

```powershell
python scripts/analyze_iccoa_capture.py --pcap reports\iccoa.pcap
```

输出 `reports/iccoa-capture-analysis.md` 和同名 json：流列表（按字节排序）、每条流的
包数/净荷中位/每秒包数/持续时间、**首部 64 字节的 hex 预览**，以及从 mDNS 里抽出的服务名和主机名。
自检：`python scripts/analyze_iccoa_capture.py --selftest`。

## 四组时序要盯的东西

| 组 | 看什么 |
| --- | --- |
| 发现 | mDNS 服务类型与实例名（`_xxx._tcp.local`）、TXT 字段、host 名、端口；BLE 广播里有没有服务 UUID；**谁先发包** |
| 握手 | 控制端口之上的第一条报文；消息类型/长度是否固定；有没有证书/公钥交换；能力协商里出现哪些字段（分辨率、编码、声道、触控能力） |
| 媒体 | 视频流端口与方向；包长分布（固定分片 or 每包一帧）；净荷里有没有 `00 00 00 01`（H.264 起始码）或 `00 00 01`；包速率 ≈ 帧率？时间戳从哪来 |
| 音频 | 是裸 PCM 还是 AAC；采样率/声道；包周期（10 ms / 20 ms 特征明显） |
| 输入 | 手机发出的触控报文长度与坐标编码；对照我们 `scale_touch` 缩放后的值，确认坐标系是屏幕坐标还是归一化坐标 |

**能力协商那一段最值钱**：它直接告诉我们一台「合格的 ICCOA Carlink 车机端」该向手机声明什么，
可以直接填进 `IccoaCapabilities`。

## 记录格式

采集完成后新建 `docs/iccoa-capture.md`，按 `docs/clean-room.md` 的「实现记录」要求写：

- 行为来源：抓包文件 SHA-256、采集日期、盒子型号/固件版本、手机型号/系统版本
- 四组时序：每组的报文编号区间 + 结论
- 对应测试向量：将来 `IccoaCodec` 的单元测试要用的真实报文样本（截取必要字节，不要全量再分发）
- 许可证与 NOTICE 影响
- **无法验证的假设**：明确列出哪些是推断

## 最小采集流程（抄这个执行）

0. 先跑 `iccoa_recon.py` 和 `iccoa_box_probe.py`，把「是不是 AP / 信道 / 盒子端口 / PSK 在哪」确定下来。
1. 停车、通电，手机与盒子都准备好。
2. 打开蓝牙 HCI snoop log；`adb logcat -c`。
3. 开始抓包（盒子上的 tcpdump，或监听模式）。
4. 让手机**先连上盒子**（配对/BLE 阶段）——这一步最容易被漏抓。
5. 启动投屏，停在**静止画面**至少 20 秒。
6. 在车机屏上点一个**确定的点**（例如左上角 (100, 100)），再滑动一次。
7. 播放一段**静音媒体** 10 秒，说一句话测试麦克风。
8. 停止抓包，取回 pcap 与两侧日志。
9. 跑 `analyze_iccoa_capture.py`，把结果和原始文件一起归档。

第 4~7 步全部做到，四组时序一次就能拿齐。
