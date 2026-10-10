# 用 DiPlay 搭一个 CarPlay 台架（让盒子在没有车的情况下开工）

## 为什么需要台架

实测（见 `docs/iccoa-capture.md` 2c）：盒子插在普通电脑上时**不会建热点**，USB 侧只停在
`WPD 05AC:12A8` 的 iPhone 身份上（设备节点状态 Error，MTP 栈起不来）。原因是：盒子扮演的是
**iPhone**，它要等主机按 CarPlay/iAP2 流程跟它完成识别，之后才会拉起无线投影。

所以「让盒子开工」需要一个会说 CarPlay 的**主机（车机角色）**。

## DiPlay 是什么

- 仓库：`shihabal3amri/DiPlay`，「Independent CarPlay **receiver** for compatible Android head units」。
- 许可：**GPL-3.0**。**可以读、可以用来搭台架；但不允许把它的代码抄进本仓库**（本仓库是 MIT，
  这也是 `docs/clean-room.md` 的要求）。
- 形态：Android APK，包名 `com.shihab.diplay`，0.2.16，要求 **Android 7.1+**。
- 它扮演 **CarPlay 接收端（车机）**——正好是盒子需要看到的那个主机。

两条与我们有直接关系的实现细节：

1. **USB 有线模式走 NCM（USB 以太网）+ 本地 VPNService**（其更新日志里有「USB 修正 NCM 帧」）。
2. 无线模式支持三种：车机自带热点 / **Wi-Fi Direct** / 同一局域网。

第 1 条很重要，见下面「真正值钱的地方」。

## 宿主怎么选

| 方案 | 评价 |
| --- | --- |
| **旧安卓手机 / 平板 + OTG 线** | **首选**。安卓手机天然是 USB Host，DiPlay 也是给安卓的；插上 OTG 即可，还能顺便 `adb logcat` 看 CarPlay 握手过程 |
| Android-x86 / Bliss OS 虚拟机 + USB 直通 | 次选。用 QEMU `-device usb-host` 或 VirtualBox USB 过滤器把盒子直通进客机 |
| 官方 Android Studio 模拟器（AVD） | **不要用**：不支持任意 USB 设备直通，白费时间 |

供电方面盒子靠 USB 取电，OTG 口应能带动；如果反复重启，改用带外部供电的 OTG 集线器。

## 真正值钱的地方：盒子可能会切换成 NCM 网络模式

DiPlay 的 USB 路径是 NCM，而盒子之前在普通电脑上只枚举出 PTP/MTP 界面。两者合起来说明：

> **盒子在拿到正确的 iAP2/CarPlay 握手后，很可能会重新枚举成 NCM（USB 以太网）模式** ——
> 也就是宿主侧会**多出一张网卡**，我们等于白捡一条通往盒子的 IP 路径。

到那时可以做的：

1. `ipconfig /all` 看新网卡和对端地址 → 用 `scripts\iccoa_box_probe.py --host <对端地址>` 扫端口、读 Web 页；
2. 如果盒子在这条链路上暴露了 telnet / adb / Web 调试页 → **直接在盒子上 `tcpdump` 抓 ICCOA**，
   这是目前唯一不需要监听网卡、不需要 root 手机就能拿到字节的路径；
3. 顺带把 CarPlay 侧的握手过程看全（`adb logcat` 读宿主上 DiPlay 的日志）。

## 必须说清的边界

- **DiPlay 不会给出 ICCOA 的字节。** ICCOA 链路在盒子自己的 Wi-Fi 上，DiPlay 只能让盒子从
  「待机」变成「开工」，并可能额外提供上面那条 NCM 的 IP 路径。
- **最大未知数**：盒子会不会因为 DiPlay 不是「真 MFi 车机」而拒绝启动 CarPlay。只能实测。
  （我们分析过的那个 CarWith→CarPlay APK 就是 `trust-chain-verified=false` 走的，两边都跳过
  MFi 验证的话反而有戏。）
- 台架起来后盒子**不在车里**，所以这也是验证「盒子是否真的需要车机」的实验。

## 台架起来后按顺序看这四件事

1. 宿主上盒子 USB 节点的状态是否从 `Error` 变成正常（`scripts\usb_probe.ps1 -Mode diff`）。
2. 宿主是否**多出一张网卡**（`ipconfig /all`）——有的话记下它的地址和子网。
3. 电脑做一次 Wi-Fi 扫描（`netsh wlan show networks mode=bssid`）：盒子这次有没有建出可扫描到的
   AP，或者只有 P2P 设备（后者会解释为什么之前「连不上盒子热点」）。
4. 手机端 ICCOA 投屏能不能起来。起来了就说明盒子已经进入完整工作状态。

第 1、2 步的结果直接决定我们接下来是「用 NCM 那条 IP 路径进盒子」还是「回到监听模式」。

## 不依赖台架的并行动作

台架是「让盒子开工」的手段，而**手机侧的事实现在就能拿**：只要手机和盒子在一起，
`scripts/iccoa_recon.py` 与 `scripts/iccoa_phone_logs.py` 立刻能给出传输方式、端口和协商参数。
两条线并行推进，互不阻塞。
