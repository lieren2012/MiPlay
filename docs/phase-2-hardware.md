# 阶段 2：Pi Zero 2 W 真实 USB 验证

## 目的

阶段 2 只验证真实 USB Device/OTG 和车机识别，不承担最终性能结论。Pi Zero 2 W 不是已确认的最终硬件。

## 建议物料

- Raspberry Pi Zero 2 W、microSD、稳定 5V 电源和散热片。
- USB OTG 数据线、数据/供电分离线或带电源注入的 USB Hub。
- USB 电压/电流表、备用保险丝或带保护的 12V 转 5V 电源。
- 可选 USB 协议分析仪、逻辑分析仪和已知可用的 CarPlay 转换器。

## 接线原则

1. 数据线必须连接 Pi 的 USB OTG 口和车机 CarPlay USB 口。
2. 若车机端口供电不足，使用独立 5V 电源；禁止把不稳定电压直接送入 Pi。
3. 所有设备共地，电源线必须带过流和反接保护。
4. 调试供电和换线必须停车完成。
5. 先使用桌面 USB 主机测试枚举，再接入车辆。

## Linux 预检

```bash
ls -l /sys/class/udc
mount | grep configfs
cargo run -p bridge-lab -- preflight
```

至少应看到一个 UDC，并能写入 `/sys/kernel/config/usb_gadget`。

## 分阶段验证

1. 主机侧确认设备描述符、配置、字符串和重置行为。
2. 车机侧确认 CarPlay 图标或设备发现变化，记录完整枚举日志。
3. 实现 role-switch、iAP2 和 NCM；NCM 不通时不得启动视频。
4. 建立手机 Wi-Fi/BT 和 CarLife 最小会话。
5. 打通一帧 H.264、一个触控事件和返回键。

## 失败分类

- `ENUM_FAIL`：车机未看到设备或设备描述符不兼容。
- `ROLE_SWITCH_FAIL`：模式切换后车机复位、掉电或不再枚举。
- `IAP2_FAIL`：识别阶段通过但认证、会话或消息版本失败。
- `NCM_FAIL`：接口存在但链路、地址或组播不稳定。
- `CARLIFE_FAIL`：手机不发现开发板或车机协议握手失败。
- `VIDEO_FAIL`：会话建立但 H.264 参数、时间戳或传输不兼容。

## 通过条件

- 君越在 30 分钟内重复识别开发板。
- 能显示最小视频并回传触控。
- 拔插后可恢复，原车功能无异常。
- 任何 CarPlay 发送端假设仍有未验证项时，不得进入阶段 3 的完整音频工作。
