#!/usr/bin/env python3
"""Read-only analysis of a captured ICCOA Carlink session.

Input is a classic libpcap file produced by tcpdump/Wireshark -- for example a
capture taken on the box, or a monitor-mode capture of the phone-to-box link.

The report answers the questions the adapter needs before any codec can be
written:

  * which endpoints exist, on which protocol and port
  * which flow looks like video, which looks like audio, which looks periodic
  * which mDNS/Bonjour service names and hosts are announced
  * what the first bytes of each flow look like, so a frame header can be
    identified

This script never transmits and never modifies the capture.
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

PCAP_MAGICS = {
    b"\xd4\xc3\xb2\xa1": ("<", "us"),
    b"\xa1\xb2\xc3\xd4": (">", "us"),
    b"\x4d\x3c\xb2\xa1": ("<", "ns"),
    b"\xa1\xb2\x3c\x4d": (">", "ns"),
}

DLT_NULL = 0
DLT_ETHERNET = 1
DLT_RAW = 101
DLT_LINUX_SLL = 113

PROTO_TCP = 6
PROTO_UDP = 17

MDNS_PORT = 5353


class PcapError(RuntimeError):
    pass


def read_pcap(path: Path) -> tuple[int, list[tuple[float, bytes]]]:
    """Returns (linktype, [(timestamp_seconds, frame_bytes)])."""
    data = path.read_bytes()
    if len(data) < 24:
        raise PcapError("file is shorter than a pcap global header")
    magic = data[:4]
    if magic not in PCAP_MAGICS:
        if magic == b"\x0a\x0d\x0d\x0a":
            raise PcapError(
                "this looks like pcapng; convert it with "
                "`editcap -F pcap <in> <out>` or `tshark -F pcap`"
            )
        raise PcapError(f"unrecognised pcap magic {magic!r}")

    endian, resolution = PCAP_MAGICS[magic]
    _, _, _, _, _, linktype = struct.unpack(endian + "HHiIII", data[4:24])
    divisor = 1_000_000 if resolution == "us" else 1_000_000_000

    frames: list[tuple[float, bytes]] = []
    offset = 24
    while offset + 16 <= len(data):
        ts_sec, ts_frac, incl_len, _ = struct.unpack(endian + "IIII", data[offset : offset + 16])
        offset += 16
        if offset + incl_len > len(data):
            break
        frames.append((ts_sec + ts_frac / divisor, data[offset : offset + incl_len]))
        offset += incl_len
    return linktype, frames


class Packet:
    __slots__ = ("protocol", "src", "dst", "sport", "dport", "payload", "flags")

    def __init__(
        self,
        protocol: int,
        src: str,
        dst: str,
        sport: int,
        dport: int,
        payload: bytes,
        flags: int,
    ) -> None:
        self.protocol = protocol
        self.src = src
        self.dst = dst
        self.sport = sport
        self.dport = dport
        self.payload = payload
        self.flags = flags

    @property
    def protocol_name(self) -> str:
        return {PROTO_TCP: "tcp", PROTO_UDP: "udp"}.get(self.protocol, str(self.protocol))


def _ipv4_address(raw: bytes) -> str:
    return ".".join(str(part) for part in raw)


def decode_frame(linktype: int, frame: bytes) -> Packet | None:
    """Strips the link layer and returns the transport packet, if any."""
    if linktype == DLT_ETHERNET:
        if len(frame) < 14:
            return None
        ethertype = struct.unpack(">H", frame[12:14])[0]
        payload = frame[14:]
        if ethertype == 0x8100 and len(payload) >= 4:  # single VLAN tag
            ethertype = struct.unpack(">H", payload[2:4])[0]
            payload = payload[4:]
        if ethertype != 0x0800:
            return None
    elif linktype == DLT_RAW:
        payload = frame
    elif linktype == DLT_LINUX_SLL:
        if len(frame) < 16:
            return None
        payload = frame[16:]
    elif linktype == DLT_NULL:
        if len(frame) < 4:
            return None
        payload = frame[4:]
    else:
        return None

    if len(payload) < 20 or payload[0] >> 4 != 4:
        return None
    ihl = (payload[0] & 0x0F) * 4
    if ihl < 20 or len(payload) < ihl + 4:
        return None
    protocol = payload[9]
    if protocol not in (PROTO_TCP, PROTO_UDP):
        return None
    src = _ipv4_address(payload[12:16])
    dst = _ipv4_address(payload[16:20])
    transport = payload[ihl:]

    if protocol == PROTO_TCP:
        if len(transport) < 20:
            return None
        sport, dport = struct.unpack(">HH", transport[0:4])
        offset = (transport[12] >> 4) * 4
        if offset < 20 or len(transport) < offset:
            return None
        return Packet(protocol, src, dst, sport, dport, transport[offset:], transport[13])
    sport, dport, length = struct.unpack(">HHH", transport[0:6])
    body = transport[8:]
    if length >= 8:
        body = body[: max(length - 8, 0)]
    return Packet(protocol, src, dst, sport, dport, body, 0)


def extract_dns_names(payload: bytes) -> list[str]:
    """Pulls label-encoded DNS names out of an mDNS/Bonjour payload."""
    found: list[str] = []
    for start in range(len(payload)):
        labels: list[str] = []
        index = start
        while index < len(payload):
            length = payload[index]
            if length == 0:
                if len(labels) >= 2:
                    found.append(".".join(labels))
                break
            if length > 63 or index + 1 + length > len(payload):
                break
            chunk = payload[index + 1 : index + 1 + length]
            if any(byte < 32 or byte > 126 for byte in chunk):
                break
            labels.append(chunk.decode("ascii"))
            if len(labels) > 12:
                break
            index += 1 + length
        if index >= len(payload):
            continue

    unique: list[str] = []
    for name in found:
        if name not in unique:
            unique.append(name)
    # Drop names that are only a suffix of a longer match at the same location.
    return [name for name in unique if not any(other != name and other.endswith(name) for other in unique)]


def service_names(names: Iterable[str]) -> list[str]:
    interesting = []
    for name in names:
        lowered = name.lower()
        if "_tcp" in lowered or "_udp" in lowered or "local" in lowered:
            if name not in interesting:
                interesting.append(name)
    return interesting


class Flow:
    def __init__(self, protocol: str, src: str, sport: int, dst: str, dport: int) -> None:
        self.protocol = protocol
        self.src = src
        self.sport = sport
        self.dst = dst
        self.dport = dport
        self.packets = 0
        self.bytes = 0
        self.payload_packets = 0
        self.first_ts: float | None = None
        self.last_ts: float | None = None
        self.sizes: list[int] = []
        self.preview: bytes = b""
        self.names: list[str] = []

    def add(self, ts: float, packet: Packet) -> None:
        self.packets += 1
        self.bytes += len(packet.payload)
        if self.first_ts is None:
            self.first_ts = ts
        self.last_ts = ts
        if not packet.payload:
            return
        self.payload_packets += 1
        self.sizes.append(len(packet.payload))
        if not self.preview:
            self.preview = packet.payload[:64]
        if packet.dport == MDNS_PORT or packet.sport == MDNS_PORT:
            for name in service_names(extract_dns_names(packet.payload)):
                if name not in self.names:
                    self.names.append(name)

    @property
    def key(self) -> str:
        return f"{self.protocol} {self.src}:{self.sport} -> {self.dst}:{self.dport}"

    def summary(self) -> dict[str, Any]:
        duration = (self.last_ts or 0.0) - (self.first_ts or 0.0)
        median = 0
        if self.sizes:
            ordered = sorted(self.sizes)
            median = ordered[len(ordered) // 2]
        return {
            "flow": self.key,
            "protocol": self.protocol,
            "src": f"{self.src}:{self.sport}",
            "dst": f"{self.dst}:{self.dport}",
            "packets": self.packets,
            "bytes": self.bytes,
            "payload_packets": self.payload_packets,
            "duration_s": round(duration, 6),
            "payload_bytes_min": min(self.sizes) if self.sizes else 0,
            "payload_bytes_median": median,
            "payload_bytes_max": max(self.sizes) if self.sizes else 0,
            "packets_per_second": round(self.packets / duration, 2) if duration > 0 else 0.0,
            "preview_hex": self.preview.hex(" "),
            "preview_ascii": "".join(chr(b) if 32 <= b < 127 else "." for b in self.preview),
            "announced_names": self.names,
        }


def analyze(path: Path, max_flows: int) -> dict[str, Any]:
    linktype, frames = read_pcap(path)
    flows: dict[tuple, Flow] = {}
    announced: list[str] = []
    total_bytes = 0
    first_ts = None
    last_ts = None

    for ts, frame in frames:
        packet = decode_frame(linktype, frame)
        if packet is None:
            continue
        total_bytes += len(packet.payload)
        first_ts = ts if first_ts is None else first_ts
        last_ts = ts
        key = (packet.protocol, packet.src, packet.sport, packet.dst, packet.dport)
        flow = flows.get(key)
        if flow is None:
            flow = Flow(packet.protocol_name, packet.src, packet.sport, packet.dst, packet.dport)
            flows[key] = flow
        flow.add(ts, packet)
        for name in flow.names:
            if name not in announced:
                announced.append(name)

    ordered = sorted(flows.values(), key=lambda item: item.bytes, reverse=True)[:max_flows]
    return {
        "capture": str(path),
        "linktype": linktype,
        "frames": len(frames),
        "media_bytes": total_bytes,
        "first_ts": first_ts,
        "last_ts": last_ts,
        "duration_s": round((last_ts - first_ts), 6) if first_ts and last_ts else 0.0,
        "flow_count": len(flows),
        "announced_names": announced,
        "flows": [flow.summary() for flow in ordered],
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# ICCOA 抓包分析",
        "",
        f"- 文件：`{report['capture']}`",
        f"- 链路类型：{report['linktype']}，帧数：{report['frames']}",
        f"- 时长：{report['duration_s']} s，传输层净荷：{report['media_bytes']} 字节",
        f"- 流数量：{report['flow_count']}（按字节排序取前 {len(report['flows'])} 条）",
        "",
        "## 广播/发现名称",
        "",
    ]
    if report["announced_names"]:
        for name in report["announced_names"]:
            lines.append(f"- `{name}`")
    else:
        lines.append("- 未在抓包中发现 mDNS/服务名（可能抓包时机晚于发现阶段）")
    lines += ["", "## 流量（按字节降序）", ""]
    lines.append("| 流 | 包数 | 字节 | 净荷中位 | 每秒包 | 持续时间 s |")
    lines.append("| --- | --- | --- | --- | --- | --- |")
    for flow in report["flows"]:
        lines.append(
            f"| `{flow['flow']}` | {flow['packets']} | {flow['bytes']} | "
            f"{flow['payload_bytes_median']} | {flow['packets_per_second']} | {flow['duration_s']} |"
        )
    lines += ["", "## 每条流的首部字节", ""]
    for flow in report["flows"]:
        if not flow["preview_hex"]:
            continue
        lines.append(f"### `{flow['flow']}`")
        lines.append("")
        lines.append(f"- 净荷范围：{flow['payload_bytes_min']}..{flow['payload_bytes_max']} 字节")
        lines.append(f"- hex： `{flow['preview_hex'][:180]}`")
        lines.append(f"- ascii：`{flow['preview_ascii'][:64]}`")
        lines.append("")
    lines.append("把这份报告和原始 pcap 一起放进 `docs/iccoa-capture.md` 的采集记录里。")
    lines.append("")
    return "\n".join(lines)


def _dns_name(name: str) -> bytes:
    out = b""
    for label in name.split("."):
        out += bytes([len(label)]) + label.encode("ascii")
    return out + b"\x00"


def _ethernet_ipv4_udp(src: str, dst: str, sport: int, dport: int, payload: bytes) -> bytes:
    ethernet = b"\xff" * 6 + b"\x02\x00\x00\x00\x00\x01" + b"\x08\x00"
    udp = struct.pack(">HHHH", sport, dport, 8 + len(payload), 0)
    total = 20 + len(udp) + len(payload)
    ip = struct.pack(
        ">BBHHHBBH4s4s",
        0x45,
        0,
        total,
        0,
        0,
        64,
        PROTO_UDP,
        0,
        bytes(int(part) for part in src.split(".")),
        bytes(int(part) for part in dst.split(".")),
    )
    return ethernet + ip + udp + payload


def _ethernet_ipv4_tcp(src: str, dst: str, sport: int, dport: int, payload: bytes) -> bytes:
    ethernet = b"\xff" * 6 + b"\x02\x00\x00\x00\x00\x01" + b"\x08\x00"
    tcp = struct.pack(">HHIIBBHHH", sport, dport, 1, 1, 5 << 4, 0x18, 65535, 0, 0)
    total = 20 + len(tcp) + len(payload)
    ip = struct.pack(
        ">BBHHHBBH4s4s",
        0x45,
        0,
        total,
        1,
        0,
        64,
        PROTO_TCP,
        0,
        bytes(int(part) for part in src.split(".")),
        bytes(int(part) for part in dst.split(".")),
    )
    return ethernet + ip + tcp + payload


def build_fixture(path: Path) -> None:
    """Writes a tiny synthetic capture used by --selftest."""
    mdns = _dns_name("Box._carlink-test._tcp.local") + struct.pack(">HH", 12, 1)
    frames = [
        (1.0, _ethernet_ipv4_udp("192.168.43.1", "224.0.0.251", MDNS_PORT, MDNS_PORT, mdns)),
        (1.1, _ethernet_ipv4_tcp("192.168.43.1", "192.168.43.5", 5000, 40000, bytes(1024))),
        (1.2, _ethernet_ipv4_tcp("192.168.43.1", "192.168.43.5", 5000, 40000, bytes(1024))),
        (1.3, _ethernet_ipv4_tcp("192.168.43.1", "192.168.43.5", 5000, 40000, bytes(512))),
        (1.4, _ethernet_ipv4_tcp("192.168.43.5", "192.168.43.1", 40000, 5000, b"\x02\x03\x00\x05")),
    ]
    blob = bytearray()
    blob += struct.pack("<I", 0xA1B2C3D4)
    blob += struct.pack("<HHiIII", 2, 4, 0, 0, 262_144, DLT_ETHERNET)
    for ts, frame in frames:
        seconds = int(ts)
        micros = int(round((ts - seconds) * 1_000_000))
        blob += struct.pack("<IIII", seconds, micros, len(frame), len(frame))
        blob += frame
    path.write_bytes(bytes(blob))


def selftest() -> int:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        pcap = Path(tmp) / "fixture.pcap"
        build_fixture(pcap)
        report = analyze(pcap, max_flows=10)

        assert report["linktype"] == DLT_ETHERNET, report["linktype"]
        assert report["frames"] == 5, report["frames"]
        assert "Box._carlink-test._tcp.local" in report["announced_names"], report["announced_names"]

        by_flow = {flow["flow"]: flow for flow in report["flows"]}
        upstream = "tcp 192.168.43.1:5000 -> 192.168.43.5:40000"
        assert upstream in by_flow, list(by_flow)
        assert by_flow[upstream]["packets"] == 3, by_flow[upstream]
        assert by_flow[upstream]["payload_bytes_max"] == 1024, by_flow[upstream]
        assert by_flow[upstream]["preview_hex"].startswith("00 00"), by_flow[upstream]

        markdown = render_markdown(report)
        assert "192.168.43.1:5000" in markdown
        assert "Box._carlink-test._tcp.local" in markdown

        # The DNS scanner must not invent names out of random binary payloads.
        assert extract_dns_names(b"\x01\x02\x03\x04\x05\x06") == []

    print("selftest ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pcap", type=Path, help="capture file to analyse")
    parser.add_argument("--report", type=Path, default=Path("reports/iccoa-capture-analysis.md"))
    parser.add_argument("--json", dest="json_path", type=Path, default=Path("reports/iccoa-capture-analysis.json"))
    parser.add_argument("--max-flows", type=int, default=25, help="flows to keep, largest first")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    if not args.pcap:
        parser.error("--pcap is required unless --selftest is used")

    try:
        report = analyze(args.pcap, args.max_flows)
    except PcapError as error:
        print(f"analysis failed: {error}", file=sys.stderr)
        return 1

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(render_markdown(report), encoding="utf-8")
    args.json_path.parent.mkdir(parents=True, exist_ok=True)
    args.json_path.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    print(f"frames: {report['frames']}  flows: {report['flow_count']}  duration: {report['duration_s']} s")
    for name in report["announced_names"]:
        print(f"announced: {name}")
    for flow in report["flows"][:8]:
        print(f"- {flow['flow']}  packets={flow['packets']} bytes={flow['bytes']}")
    print(f"report: {args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
