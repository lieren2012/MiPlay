#!/usr/bin/env python3
"""Phone-side reconnaissance for an ICCOA Carlink session.

Run this while the phone is actively mirroring to the ICCOA -> CarPlay box.
It answers the questions that decide how the session has to be captured:

  * which side is the Wi-Fi access point (box or phone)
  * the box IP, the phone IP and the Wi-Fi channel
  * every TCP/UDP endpoint the phone is talking to, in particular the ones
    pointing at the box

It only reads state that `adb shell` can already read; it does not modify the
phone, does not root it and does not disable any protection.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

CARLINK_KEYWORDS = ("carlink", "carwith", "carlife", "iccoa", "carplay", "autolink", "castfwk")


class AdbError(RuntimeError):
    pass


def run_adb(adb: str, serial: str | None, args: list[str]) -> str:
    command = [adb]
    if serial:
        command += ["-s", serial]
    command += args
    result = subprocess.run(command, capture_output=True, text=True, errors="replace")
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip() or "no output"
        raise AdbError(f"{' '.join(args)} failed: {detail}")
    return result.stdout


def run_adb_soft(adb: str, serial: str | None, args: list[str]) -> str:
    try:
        return run_adb(adb, serial, args)
    except AdbError:
        return ""


def choose_serial(adb: str, requested: str | None) -> str:
    output = run_adb(adb, None, ["devices", "-l"])
    devices = [
        line.split()[0]
        for line in output.splitlines()[1:]
        if len(line.split()) >= 2 and line.split()[1] == "device"
    ]
    if requested:
        if requested not in devices:
            raise AdbError(f"requested device {requested} is not connected and authorized")
        return requested
    if not devices:
        raise AdbError("no authorized Android device found; enable USB debugging")
    if len(devices) > 1:
        raise AdbError("multiple Android devices found; pass --serial explicitly")
    return devices[0]


def freq_to_channel(freq_mhz: int) -> int | None:
    """Maps a Wi-Fi centre frequency to its channel number."""
    if 2_412 <= freq_mhz <= 2_472:
        return (freq_mhz - 2_407) // 5
    if freq_mhz == 2_484:
        return 14
    if 5_180 <= freq_mhz <= 5_880:
        return (freq_mhz - 5_000) // 5
    if 5_955 <= freq_mhz <= 7_115:
        return (freq_mhz - 5_955) // 5 + 1
    return None


def parse_wifi_status(text: str) -> dict[str, Any]:
    """Parses `adb shell cmd wifi status` without depending on its layout."""
    result: dict[str, Any] = {
        "enabled": "wifi is enabled" in text.lower(),
        "ssid": None,
        "bssid": None,
        "ip": None,
        "frequency_mhz": None,
        "channel": None,
        "rssi_dbm": None,
        "link_speed_mbps": None,
    }
    ssid = re.search(r'SSID:\s*"([^"]*)"', text)
    if ssid:
        result["ssid"] = ssid.group(1)
    bssid = re.search(r"BSSID:\s*([0-9a-fA-F:]{17})", text)
    if bssid:
        result["bssid"] = bssid.group(1).lower()
    ip = re.search(r"IP:\s*/?(\d+\.\d+\.\d+\.\d+)", text)
    if ip:
        result["ip"] = ip.group(1)
    freq = re.search(r"Frequency:\s*(\d+)\s*MHz", text)
    if freq:
        result["frequency_mhz"] = int(freq.group(1))
        result["channel"] = freq_to_channel(result["frequency_mhz"])
    rssi = re.search(r"RSSI:\s*(-?\d+)", text)
    if rssi:
        result["rssi_dbm"] = int(rssi.group(1))
    speed = re.search(r"Link speed:\s*(\d+)\s*Mbps", text)
    if speed:
        result["link_speed_mbps"] = int(speed.group(1))
    return result


def parse_ip_route(text: str) -> dict[str, Any]:
    """Parses `adb shell ip -4 addr` plus `ip route` output."""
    result: dict[str, Any] = {"interfaces": [], "gateway": None, "default_interface": None}
    iface = None
    for line in text.splitlines():
        header = re.match(r"^\d+:\s+(\S+):", line)
        if header:
            iface = header.group(1)
            result["interfaces"].append({"name": iface, "addresses": []})
            continue
        address = re.match(r"^\s+inet\s+(\d+\.\d+\.\d+\.\d+)/(\d+)", line)
        if address and result["interfaces"]:
            result["interfaces"][-1]["addresses"].append(
                f"{address.group(1)}/{address.group(2)}"
            )
        if line.startswith("default ") and "via" in line:
            result["gateway"] = line.split("via")[1].split()[0]
            if "dev" in line:
                result["default_interface"] = line.split("dev")[1].split()[0]
    return result


def _decode_proc_address(value: str) -> str:
    """Decodes the little-endian IPv4:port form used by /proc/net/*."""
    host, _, port = value.partition(":")
    if len(host) == 8:
        octets = [str(int(host[i : i + 2], 16)) for i in (6, 4, 2, 0)]
        return f"{'.'.join(octets)}:{int(port, 16)}"
    return f"{host}:{int(port, 16)}"


def parse_proc_net(text: str, protocol: str) -> list[dict[str, Any]]:
    """Parses /proc/net/tcp or /proc/net/udp into local/remote endpoints."""
    sockets = []
    for line in text.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 4:
            continue
        local, remote, state = fields[1], fields[2], fields[3]
        local_host, _, local_port = local.partition(":")
        remote_host, _, remote_port = remote.partition(":")
        if len(local_host) != 8 or len(remote_host) != 8:
            continue
        if len(local_port) != 4 or len(remote_port) != 4:
            continue
        sockets.append(
            {
                "protocol": protocol,
                "local": _decode_proc_address(local),
                "remote": _decode_proc_address(remote),
                "state": state,
                "connected": remote != "00000000:0000",
            }
        )
    return sockets


CAPABILITY_BINARIES = ("su", "tcpdump", "nc", "curl", "ip")


def collect_capabilities(adb: str, serial: str) -> dict[str, Any]:
    """Reports which capture routes the phone itself can support.

    A phone with `su` can capture its own Wi-Fi interface, which removes the
    need for a second client on the box hotspot entirely. Without it, the
    phone cannot capture and the session has to be taken on the box or in
    monitor mode.
    """
    binaries = {}
    for name in CAPABILITY_BINARIES:
        found = run_adb_soft(adb, serial, ["shell", "command", "-v", name]).strip()
        binaries[name] = found or None
    rooted = binaries["su"] is not None
    routes: list[str] = []
    if rooted and binaries["tcpdump"] is not None:
        routes.append("phone-tcpdump")
    if rooted:
        routes.append("phone-root-available")
    if not rooted:
        routes.append("needs-monitor-mode-or-box-shell")
    return {"binaries": binaries, "rooted": rooted, "routes": routes}


def collect(adb: str, serial: str) -> dict[str, Any]:
    wifi_text = run_adb_soft(adb, serial, ["shell", "cmd", "wifi", "status"])
    route_text = run_adb_soft(adb, serial, ["shell", "ip", "-4", "addr"])
    route_text += run_adb_soft(adb, serial, ["shell", "ip", "route"])
    tcp = parse_proc_net(run_adb_soft(adb, serial, ["shell", "cat", "/proc/net/tcp"]), "tcp")
    udp = parse_proc_net(run_adb_soft(adb, serial, ["shell", "cat", "/proc/net/udp"]), "udp")

    packages = []
    listing = run_adb_soft(adb, serial, ["shell", "pm", "list", "packages"])
    for raw in listing.splitlines():
        match = re.match(r"package:(\S+)", raw.strip())
        if not match:
            continue
        package = match.group(1)
        if any(keyword in package.lower() for keyword in CARLINK_KEYWORDS):
            packages.append(package)

    network_report = parse_ip_route(route_text)
    report = {
        "collected_at_utc": dt.datetime.now(dt.UTC).isoformat(),
        "device_serial": serial,
        "wifi": parse_wifi_status(wifi_text),
        "network": network_report,
        "sockets": tcp + udp,
        "candidate_packages": sorted(packages),
        "role_hint": role_hint(network_report),
        "capabilities": collect_capabilities(adb, serial),
    }
    report["carlink_endpoints"] = carlink_endpoints(report)
    report["capture_ladder"] = capture_ladder(report)
    return report


def box_ip(report: dict[str, Any]) -> str | None:
    return report.get("network", {}).get("gateway")


def carlink_endpoints(report: dict[str, Any]) -> list[dict[str, Any]]:
    """Endpoints whose remote address is the default gateway (the box in AP mode)."""
    gateway = box_ip(report)
    if not gateway:
        return []
    return [
        socket
        for socket in report.get("sockets", [])
        if socket.get("connected") and socket["remote"].startswith(f"{gateway}:")
    ]


def _public_endpoints(report: dict[str, Any]) -> list[dict[str, Any]]:
    endpoints = []
    for socket in report.get("sockets", []):
        if not socket.get("connected"):
            continue
        remote_host = socket["remote"].rsplit(":", 1)[0]
        if remote_host.startswith(("192.168.", "10.", "172.", "100.64.")):
            continue
        endpoints.append(socket)
    return endpoints


def role_hint(network: dict[str, Any]) -> str:
    """Says which side looks like the access point.

    A phone that joined a hotspot has a default route through a wlan interface.
    A phone that is hosting the hotspot exposes ap/softap/swlan interfaces and
    usually has no default route at all.
    """
    names = [interface["name"] for interface in network.get("interfaces", [])]
    if any(re.match(r"^(ap|softap|swlan)", name) for name in names):
        return "phone-likely-ap"
    gateway = network.get("gateway")
    default_interface = network.get("default_interface") or ""
    if gateway and default_interface.startswith("wlan"):
        return "phone-likely-client"
    return "unknown"


def capture_ladder(report: dict[str, Any]) -> list[str]:
    """Ordered by value per unit of effort, given what this run learned."""
    wifi = report.get("wifi", {})
    gateway = box_ip(report)
    capabilities = report.get("capabilities") or {}
    rooted = bool(capabilities.get("rooted"))
    steps: list[str] = []

    if wifi.get("ssid"):
        steps.append(
            f"phone is on SSID {wifi['ssid']!r} at {wifi.get('frequency_mhz')} MHz "
            f"(channel {wifi.get('channel')}); the role hint says which side hosts it"
        )
    if rooted:
        steps.append(
            "phone has su: capture on the phone itself with "
            "`adb shell su -c 'tcpdump -i wlan0 -s 0 -w /sdcard/iccoa.pcap'` - no second client needed"
        )
    else:
        steps.append(
            "phone has no su: it cannot capture, so the bytes have to come from the box or from the air"
        )
    if gateway:
        steps.append(
            f"open http://{gateway}/ in the PHONE browser: firmware version, PSK, and any debug/log page"
        )
        steps.append(
            "if the box has a shell (web page hint, adb 5555, telnet 23), capture on the box: "
            "tcpdump -i any -s 0 -w /tmp/iccoa.pcap"
        )
    steps.append("always available: adb logcat plus the Bluetooth HCI snoop log from the phone")
    steps.append(
        "monitor mode does not need to join the hotspot: capture channel "
        f"{wifi.get('channel')} from a Linux box and decrypt with the PSK read from the web page; "
        "toggle the phone Wi-Fi off and on to force a fresh association while capturing"
    )
    steps.append(
        "do NOT join the box hotspot from a second device: these boxes accept one client and the "
        "association kills the running session"
    )
    return steps


def render_markdown(report: dict[str, Any]) -> str:
    wifi = report["wifi"]
    network = report["network"]
    lines = [
        "# ICCOA 车机端侦察报告",
        "",
        f"- 采集时间：{report['collected_at_utc']}",
        f"- 设备：{report['device_serial']}",
        "",
        "## Wi-Fi",
        "",
        f"- SSID：{wifi.get('ssid')}",
        f"- BSSID：{wifi.get('bssid')}",
        f"- 频率/信道：{wifi.get('frequency_mhz')} MHz / {wifi.get('channel')}",
        f"- 手机地址：{wifi.get('ip')}",
        f"- RSSI：{wifi.get('rssi_dbm')} dBm，链路速率：{wifi.get('link_speed_mbps')} Mbps",
        "",
        "## 网络",
        "",
        f"- 角色判断：{report['role_hint']}（phone-likely-client 表示手机是客户端、盒子是 AP）",
        f"- 默认接口：{network.get('default_interface')}",
    ]
    for interface in network.get("interfaces", []):
        lines.append(f"- 接口 {interface['name']}：{', '.join(interface['addresses']) or '无地址'}")
    lines += ["", "## 指向盒子的连接", ""]
    endpoints = report["carlink_endpoints"]
    if endpoints:
        for endpoint in endpoints:
            lines.append(
                f"- {endpoint['protocol']} {endpoint['local']} -> {endpoint['remote']} "
                f"(state {endpoint['state']})"
            )
    else:
        lines.append("- 无（盒子可能不是网关，或会话当前未建立）")
    lines += ["", "## 公网方向连接（排除本网段）", ""]
    public = _public_endpoints(report)
    if public:
        for endpoint in public:
            lines.append(f"- {endpoint['protocol']} {endpoint['local']} -> {endpoint['remote']}")
    else:
        lines.append("- 无")
    lines += ["", "## 候选应用包", ""]
    for package in report["candidate_packages"]:
        lines.append(f"- {package}")
    lines += ["", "## 采集能力（手机侧）", ""]
    capabilities = report.get("capabilities") or {}
    if capabilities:
        binaries = capabilities.get("binaries", {})
        found = ", ".join(f"{name}={path}" for name, path in binaries.items() if path)
        rooted = "是" if capabilities.get("rooted") else "否"
        lines.append(f"- 手机已 root：{rooted}")
        lines.append(f"- 可用命令：{found or '无'}")
        lines.append(f"- 可用抓包路径：{'，'.join(capabilities.get('routes', []))}")
    else:
        lines.append("- 未采集")
    lines += ["", "## 抓包升级路径（按侵入性从低到高）", ""]
    for step in report["capture_ladder"]:
        lines.append(f"1. {step}")
    lines += ["", "详见 `docs/iccoa-capture.md`。", ""]
    return "\n".join(lines)


FIXTURE_WIFI = """
Wifi is enabled
WifiInfo: SSID: "ICCOA-Box-5G", BSSID: 02:1a:2b:3c:4d:5e, MAC: 02:00:00:00:00:01, IP: /192.168.43.5, Security type: 2, Supplicant state: COMPLETED, Wi-Fi standard: 4, RSSI: -45, Link speed: 72Mbps, Tx Link speed: 72Mbps, Frequency: 2437MHz, Net ID: 1, Metered hint: false, score: 60
"""

FIXTURE_ADDR = """1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN
    inet 127.0.0.1/8 scope host lo
2: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP
    inet 192.168.43.5/24 brd 192.168.43.255 scope global wlan0
default via 192.168.43.1 dev wlan0 table 1002
192.168.43.0/24 dev wlan0 proto kernel scope link src 192.168.43.5
"""

FIXTURE_TCP = """  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 052BA8C0:9C40 012BA8C0:1F90 01 00000000:00000000 02:00000000 00000000 10123        0 45678 1 0000000000000000 20 4 30 10 -1
   1: 052BA8C0:9C41 2E8B1DB7:01BB 01 00000000:00000000 02:00000000 00000000 10123        0 45679 1 0000000000000000 20 4 30 10 -1
"""

FIXTURE_UDP = """  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000:14E9 00000000:0000 07 00000000:00000000 00:00000000 00000000 10123        0 45680 2 0000000000000000 2 4 30 10 -1
"""


def selftest() -> int:
    wifi = parse_wifi_status(FIXTURE_WIFI)
    assert wifi["ssid"] == "ICCOA-Box-5G", wifi
    assert wifi["ip"] == "192.168.43.5", wifi
    assert wifi["channel"] == 6, wifi
    assert wifi["rssi_dbm"] == -45, wifi

    network = parse_ip_route(FIXTURE_ADDR)
    assert network["gateway"] == "192.168.43.1", network
    assert network["default_interface"] == "wlan0", network
    assert network["interfaces"][1]["addresses"] == ["192.168.43.5/24"], network

    tcp = parse_proc_net(FIXTURE_TCP, "tcp")
    assert tcp[0]["local"] == "192.168.43.5:40000", tcp[0]
    assert tcp[0]["remote"] == "192.168.43.1:8080", tcp[0]
    udp = parse_proc_net(FIXTURE_UDP, "udp")
    assert udp[0]["local"] == "0.0.0.0:5353", udp[0]
    assert udp[0]["connected"] is False, udp[0]

    assert freq_to_channel(2_412) == 1
    assert freq_to_channel(2_437) == 6
    assert freq_to_channel(5_180) == 36
    assert freq_to_channel(5_745) == 149
    assert freq_to_channel(300) is None

    report = {
        "collected_at_utc": "2026-01-01T00:00:00+00:00",
        "device_serial": "fixture",
        "wifi": wifi,
        "network": network,
        "sockets": tcp + udp,
        "candidate_packages": ["com.example.carlink"],
        "role_hint": role_hint(network),
        "capabilities": {"binaries": {"su": None, "tcpdump": None}, "rooted": False, "routes": ["needs-monitor-mode-or-box-shell"]},
    }
    report["carlink_endpoints"] = carlink_endpoints(report)
    report["capture_ladder"] = capture_ladder(report)
    assert len(report["carlink_endpoints"]) == 1, report["carlink_endpoints"]
    assert report["carlink_endpoints"][0]["remote"] == "192.168.43.1:8080"
    public = _public_endpoints(report)
    assert public and public[0]["remote"].endswith(":443"), public

    markdown = render_markdown(report)
    assert "192.168.43.1:8080" in markdown
    assert "channel 6" in markdown
    assert report["role_hint"] == "phone-likely-client", report["role_hint"]
    assert "phone-likely-client" in markdown
    assert "采集能力" in markdown
    assert any("one client" in step for step in report["capture_ladder"])
    print("selftest ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="ADB executable")
    parser.add_argument("--serial", help="ADB device serial")
    parser.add_argument("--report", type=Path, default=Path("reports/iccoa-recon.md"))
    parser.add_argument("--json", dest="json_path", type=Path, default=Path("reports/iccoa-recon.json"))
    parser.add_argument("--selftest", action="store_true", help="run parser fixtures and exit")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    try:
        serial = choose_serial(args.adb, args.serial)
        report = collect(args.adb, serial)
    except AdbError as error:
        print(f"recon failed: {error}", file=sys.stderr)
        return 1

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(render_markdown(report), encoding="utf-8")
    args.json_path.parent.mkdir(parents=True, exist_ok=True)
    args.json_path.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    wifi = report["wifi"]
    print(f"SSID: {wifi.get('ssid')}  channel: {wifi.get('channel')}")
    print(f"phone: {wifi.get('ip')}  gateway (suspect box): {report['network'].get('gateway')}")
    print(f"connections to the box: {len(report['carlink_endpoints'])}")
    for endpoint in report["carlink_endpoints"]:
        print(f"- {endpoint['protocol']} {endpoint['remote']}")
    print(f"report: {args.report}")
    print("mirror the phone to the box first, then run this again if the list is empty")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
