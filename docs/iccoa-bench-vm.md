# CarPlay 台架：VirtualBox + Android-x86 + DiPlay

目标：让盒子在没有车的情况下**开工**。实测过盒子插普通电脑时只停在 `WPD 05AC:12A8` 的 PTP 身份上
（节点 Error、不建热点），因为它要等主机按 CarPlay/iAP2 流程完成识别。DiPlay 扮演的就是那个主机。

## 本机前置条件（已核实）

| 项 | 结果 |
| --- | --- |
| CPU | i7-10750H，6C/12T，VT-x 已启用，SLAT 支持 |
| Hypervisor | `HypervisorPresent=False` → Hyper-V 没开，VirtualBox 可以用硬件虚拟化 |
| 内存 / C 盘 | 31.8 GB / 353 GB 空闲 |
| 已装虚拟机软件 | 无（VirtualBox / VMware / QEMU / BlueStacks 都没有） |
| 当前 shell 权限 | **非管理员** → 安装 VirtualBox 需要你点 UAC |
| 盒子的 USB 现状 | 复合设备，只有一个接口 `MI_00`（自报 `PTP`，Class 06/01/01），绑定 Apple 的
`appleusb.inf`，宿主没有多出网卡 |

## 选型

**VirtualBox 7.2.20 + Android-x86 9.0-r2**。

- 不用官方 Android 模拟器（AVD）：不支持任意 USB 设备直通。
- 不用 Hyper-V：完全不带 USB 直通。
- VirtualBox 的 USB 直通靠它自带的过滤器，**不需要替换设备驱动**（比 QEMU + Zadig 那条路干净）。
- Android-x86 9.0 = Android 9，满足 DiPlay 要求（7.1+），体积小、支持 BIOS 启动。

已下载到 `tools\vm\`（`tools/` 已在 `.gitignore` 里）：

| 文件 | 大小 | 校验 |
| --- | --- | --- |
| `VirtualBox-7.2.20-Win.exe` | 169.9 MB | 官方站点 |
| `Oracle_VirtualBox_Extension_Pack-7.2.20.vbox-extpack` | 19.1 MB | 官方站点（USB 2.0/3.0 必需） |
| `DiPlay-0.2.16.apk` | 16.5 MB | SHA-256 `3d9e4b5d…be31fc`，与官方 `SHA256SUMS.txt` 一致 ✓ |
| `android-x86-9.0-r2.iso` | 726 MB | 下载中 |

许可提醒：VirtualBox 基础版是 GPL，**Extension Pack 只允许个人使用/评估**；DiPlay 是 **GPL-3.0**，
可以读、可以装来搭台架，**不能把它的代码抄进本仓库**（本仓库 MIT）。

## 本机当前进度（已完成的配置）

虚拟机已经建好并注册，名字 `AndroidX86-DiPlay`（`C:\Users\hz\VirtualBox VMs\AndroidX86-DiPlay\`）：

| 项 | 值 |
| --- | --- |
| 类型 | Linux 2.6/3.x/4.x/5.x (64-bit)，BIOS 启动，boot1=dvd |
| 内存 / CPU | 8192 MB / 4 |
| 显存 | 128 MB，VMSVGA |
| SATA | port0 = 32 GB 动态 VDI；port1 = `C:\迅雷下载\android-x86-9.0-r2.iso` |
| 网络 | NAT，端口转发 `adb: 宿主机5555 → 客机5555` |
| USB | 已开，**xHCI (USB 3.0)** |
| USB 过滤器 | 名 `ICCOA box Apple VID`，**只有 vendorid=05ac，productid 留空** |

镜像校验：`C:\迅雷下载\android-x86-9.0-r2.iso`，761,266,176 字节（与官方一致），ISO9660 结构正常，
SHA-256 `91CEDB534BA095A0C9B3ECEEDE4147967FD27BEEA9BBA640776F787DC3555021`。

**唯一还缺的：Extension Pack 没装**（`VBoxManage list extpacks` 为 0）。没装它 VirtualBox 会拒绝
启动带 USB 2.0/3.0 的虚拟机。双击 `tools\vm\Oracle_VirtualBox_Extension_Pack-7.2.20.vbox-extpack`
→ 同意许可 → 会弹 UAC。

### 快速路径：先用 Live CD，不装到硬盘

Android-x86 的引导菜单默认项就是 `Live CD - Run Android-x86 without installation`，直接跑在内存里。
对「验证盒子认不认 DiPlay」这件事足够了，省掉分区和装 GRUB 的步骤。跑通之后再装到硬盘也不迟。

## 步骤

### 1. 装 VirtualBox ✅ 已完成（7.2.20）

双击 `tools\vm\VirtualBox-7.2.20-Win.exe` → 一路下一步（默认组件即可）。
装完双击 `tools\vm\Oracle_VirtualBox_Extension_Pack-7.2.20.vbox-extpack` → 同意许可 → 安装扩展包。

装完确认：命令行 `"%ProgramFiles%\Oracle\VirtualBox\VBoxManage.exe" --version`。

### 2. 建虚拟机 ✅ 已完成（见上面「本机当前进度」）

用 GUI 或命令行都行，参数如下：

| 项 | 值 |
| --- | --- |
| 类型 / 版本 | Linux / Other Linux (64-bit) |
| 内存 | 4096 MB（宿主 32 GB，可以给 8 GB） |
| CPU | 2~4 |
| 硬盘 | 32 GB VDI，动态分配 |
| 显卡 | VMSVGA，显存 128 MB |
| 网络 | NAT（VM 内要用 `10.0.2.2:8000` 下载 APK） |
| 声音 | 默认 |
| **USB** | 控制器选 **USB 3.0 (xHCI)**，**添加 USB 过滤器** |

> **⚠️ 关键坑：USB 过滤器只填厂商 ID，不要填产品 ID。**
> 厂商 ID 填 `05AC`（Apple），产品 ID **留空**。原因是盒子在完成 CarPlay 握手后很可能像真 iPhone
> 一样**重新枚举成另一个 PID**（甚至换一套接口配置）。如果过滤器写死了 `12A8`，切换那一刻设备就会
> 掉出虚拟机，实验直接断在这里。

### 3. 装 Android-x86

VM 设置 → 存储 → 挂载 `android-x86-9.0-r2.iso` → 启动 →
选 `Installation - Install Android-x86 to harddisk` → 创建分区（GPT，一个 ext4 分区，标记 bootable）
→ 安装 GRUB → 重启（重启后把 ISO 从光驱卸掉或用硬盘启动）。

首次启动按向导走完（可以跳过 Google 登录），确认能打开浏览器访问 `http://10.0.2.2:8000/`。

### 4. 把 DiPlay 装进虚拟机

VM 里没有共享文件夹（Android-x86 没有 Guest Additions），所以走 HTTP：

```powershell
# 宿主机上，在仓库根目录执行
python -m http.server 8000 --directory tools\vm
```

VM 的浏览器打开 `http://10.0.2.2:8000/DiPlay-0.2.16.apk` 下载安装（需要允许「安装未知来源应用」）。

想要更顺手的调试通道，可以在 Android-x86 的终端里开 adb over TCP，然后从宿主机连：

```
setprop service.adb.tcp.port 5555
stop adbd ; start adbd
```
```powershell
tools\platform-tools\adb.exe connect <VM 的 IP>:5555
```
（VM 的 IP 可以在 Android 设置 → 关于 → 状态里看到；NAT 模式下宿主机连不上 VM 的 5555，
所以这一步要在 VM 网络改成「桥接网卡」之后才可用。）

### 5. 接盒子

VM 运行中 → 设备 → USB → 勾选 `Apple Inc. iPhone`（或让过滤器自动接管）→ 盒子从 Windows 转交给 VM。

## 实测结果（本机，2026-10-10）：卡在 USB 重新枚举

台架已经全部搭起来并跑到了最后一步：

- VirtualBox 7.2.20 + Android-x86 9.0（Live，`nosetup`）+ **UART 映射成 TCP**，用串口拿到 root shell；
- DiPlay 0.2.16 已装进客户机（通过第二张光盘挂载 + `pm install`，SHA-256 与官方一致）；
- 盒子的 USB 直通成功：主机侧 `VBoxManage list usbhost` 显示 `Captured`，客户机里
  `/sys/bus/usb/devices/2-1` 的 `product` = `iPhone`、`manufacturer` = `Apple Inc.`；
- DiPlay 启动后确实认出了盒子，MFi 握手与 USB 权限都过了：

```
xcertplay-usb: mfi local offline ready protocolMajor=3 certificateBytes=607
xcertplay-usb: wired iPhone discovered vid=0x5ac pid=0x12a8
xcertplay-usb: wired iPhone USB permission granted
xcertplay-usb: carplay config chosen=null available=[1, 2, 3, 4]
xcertplay-usb: ... action=request-transition
xcertplay-usb: ... phase=REENUMERATION stage=SelectingConfiguration -> Failed
```

**卡点**：盒子切到 CarPlay 模式要靠一次 USB 重新枚举，而这一步在 VirtualBox 的 USB 直通上过不去。
现象是重新枚举之后设备在客户机里停在"未配置"状态（`/sys/bus/usb/devices/` 下只剩 `2-1`、没有
`2-1:1.0` 接口），DiPlay 的 `requireConfiguredDevice()` 直接把后续尝试全部拒绝（日志里 2~8 毫秒就
`Failed`）。从客户机侧 `echo 0/1 > .../authorized` 能把接口找回来，但仍然过不了那一次控制请求。

### 顺带拿到的协议情报（对我们自己的实现有用）

装到客户机里的 DiPlay 是 GPL-3.0 开源的，读它的 `IphoneUsbHost.kt` 可以直接看到**盒子从 iPhone 侧
切到 CarPlay 模式用的那条请求**：

```
bmRequestType = 0xC0    (vendor, device, IN)
bRequest      = 0x52    (CARPLAY_CONFIGURATION_REQUEST)
wValue        = 0
wIndex        = 0x0004  (CARPLAY_CONFIGURATION_INDEX)
返回长度      = 1 字节
```

配套的状态机命名也很直白：`USB_DISCOVERY → (MfiReady) → (DiscoveringIphone) → request-transition →
REENUMERATION → SelectingConfiguration → DATAPATHS → CONTROL`，之后才建立 iAP2/USBMUX 与 NCM 数据通道。
它还会去认一个 `IPHONE` 的 USB 配置（当前盒子报 4 套配置 `[1,2,3,4]`，DiPlay 认为没有一套符合
"CarPlay 配置"的特征，于是回退到发 `0x52` 触发切换）。

> 注意：DiPlay 是 GPL-3.0，**只读不抄**。

## 接下来能走的路

| 方案 | 代价 | 成功率 |
| --- | --- | --- |
| **A. 用一台真实安卓设备（旧手机/平板）+ OTG 转接头跑 DiPlay** | 最小，约 15 分钟 | 最高——没有直通层，重新枚举由真实 USB 控制器完成 |
| B. 把虚拟机换成 QEMU（weilnetz W64）+ `-device usb-host` + Zadig 绑 WinUSB | 约 1 小时 | 中等——QEMU 的 usb-host 会把控制传输与重新枚举更忠实地转给真实设备 |
| C. 在 **Windows 主机**上用 PyUSB 自己发那条 `0xC0/0x52/wIndex=4` 请求，把盒子切到 CarPlay 模式后再直通给虚拟机 | 需要 Zadig 换驱动（要管理员） | 不确定——切换可能成功，但后续数据通道仍要穿过直通层 |

方案 A 还有一个额外好处：真实安卓手机上可以直接 `adb logcat`，DiPlay 的日志和盒子的行为都更容易观察。

## 分层验收（哪一层挂了就知道卡在哪）

| 层 | 检查 | 失败意味着 |
| --- | --- | --- |
| L1 | Android-x86 启动，浏览器能开 `10.0.2.2:8000` | ISO/网卡配置问题，与盒子无关 |
| L2 | DiPlay 装上并能打开 | APK 下载不完整 / Android 版本不够 |
| L3 | 盒子出现在 VM 里：VM 控制台 `Alt+F1` 进 Linux 终端，`dmesg \| grep -i usb` 能看到 `05ac:12a8` | USB 过滤器或 xHCI 配置问题 |
| L4 | DiPlay 点连接后盒子**重新枚举**：VM 里 `ip addr` 是否多出一个 usb 网卡；宿主机 `scripts\usb_probe.ps1 -Mode diff` 看设备是否被 VM 拿走 | 盒子不认这个「软件车机」（MFi/iAP2 握手没过）——**这是最大的未知数** |
| L5 | **手机端 ICCOA 投屏能起来** | 前四层都过但到这层失败 → 说明盒子还需要车机的其它信号（如显示能力协商、特定 iAP2 记录） |

L5 是通过的铁证：盒子进入完整工作状态。

## 台架跑通之后拿到什么

1. 盒子的 Wi-Fi 会起来 → 从宿主机扫描（`netsh wlan show networks mode=bssid`）看它是建普通 AP 还是
   只有 P2P 设备；
2. 若 VM 里出现 usb 网卡（NCM），说明盒子切到了网络模式 → **VM 里就是 root**（Android-x86 控制台可直接
   `su`），可以在那条链路或 Wi-Fi 链路上抓包；
3. 用 `scripts\iccoa_box_probe.py --host <盒子地址>` 找它的 Web/telnet/adb 入口 → 有 shell 就能
   **在盒子上 tcpdump 抓 ICCOA**，这是最干净的取数方式。

## 仍然拿不到 / 需要注意

- **ICCOA 的字节不在这条 USB 上**。USB 是 CarPlay 侧；ICCOA 在盒子的 Wi-Fi 上。台架只是让盒子开工。
- 盒子的 Wi-Fi 若是 Wi-Fi Direct，旁观者无法解密（见 `docs/iccoa-capture.md` C 段的期望管理）。
- 盒子从 USB 取电，若在 VM 里反复掉线，改用**带外部供电的 USB 集线器**接盒子。

## 备用方案（按顺序）

1. Android-x86 9.0 起不来或 USB 异常 → 换 **Bliss OS 14.x（Android 11）**。
2. VirtualBox 的 USB 直通不行 → 换 **QEMU（weilnetz W64 版）+ `-device usb-host` + Zadig 把盒子驱动换成 WinUSB**。
3. 想要更强的抓包能力（root、tcpdump、监听模式）→ 换 **Linux 宿主**，用 Linux 上的 CarPlay 接收端
   （例如 GitHub 上的 `ztecjo/nexuscp`），或仍跑 DiPlay 但放在 Android-x86 里。
