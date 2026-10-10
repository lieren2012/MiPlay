# 阶段 0：需求与基线检查

只有以下信息完整后，才进入真实协议适配开发。配置模板位于 `config`。

## 车辆

- 记录车机完整软件版本、市场版本和 CarPlay 版本。
- 确认使用的 CarPlay USB 口形态、供电能力及点火状态下的插拔行为。
- 当前车辆信息记录为车口最高约 50W；仍需实测 USB PD 档位、数据模式下的实际 5V 电流和点火切换行为。
- 用 iPhone 记录 CarPlay 实际协商分辨率、刷新率、触控和方控能力。
- 确认倒车、电话、导航提示和麦克风由原车如何路由。
- 记录车机亮屏、唤醒、拔线和熄火后的会话行为。

## 手机

- 填写品牌、准确型号、Android 版本、安全补丁和官方 CarLife 版本。
- 确认官方 CarLife 支持无线连接，并记录启动顺序：蓝牙、Wi-Fi 直连或热点。
- 记录锁屏、后台限制、省电模式和热点开启时的行为。
- 不接受依赖 root、Xposed 或修改系统分区才能工作的方案。

## 黑盒基础

- 准备一台厂商功能正常的 CarPlay 转换器，只用于黑盒行为和协议观察。
- 不提取或再分发其固件、密钥、证书或专有库。
- 抓包只保留本机车辆和手机的会话，避免包含第三方隐私数据。
- 记录成功和失败的时间线，而不是只保留最终错误。

## 通过条件

```powershell
python scripts/check_phase0.py --vehicle config/vehicle.lacrosse-2025.json --phone config/phone.primary.json --license config/license-boundary.json
```

检查器必须通过，才能锁定阶段 1 的测试输入。`config/*.example.json` 故意包含空值，不应被当作正式配置。

详细采集步骤见 `docs/phase-0-data-collection.md`；手机连接 ADB 后可运行 `scripts/collect_phone_info.py` 自动收集系统与候选 CarLife 包信息。
