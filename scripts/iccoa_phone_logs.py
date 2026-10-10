#!/usr/bin/env python3
"""Phone-side log capture for an ICCOA Carlink session.

`adb logcat` is readable without root on the vast majority of devices, and the
vendor projection service logs far more than it should: discovery, the transport
it picked, the ports, negotiated video parameters and error paths. When the
radio cannot be captured this is the cheapest source of protocol facts.

Flow: clear the buffer, let the session run, then dump and filter.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

LOG_KEYWORDS = (
    "carlink",
    "carlife",
    "carwith",
    "iccoa",
    "carplay",
    "airplay",
    "castfwk",
    "wifip2p",
    "p2p",
    "mdns",
    "nsd",
    "bonjour",
    "handshake",
    "h264",
    "hevc",
    "avc",
    "resolution",
    "gatt",
    "ble ",
)

TIMESTAMP_RE = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+")


class AdbError(RuntimeError):
    pass


def run_adb(adb: str, serial: str | None, args: list[str], timeout: float = 120.0) -> str:
    command = [adb]
    if serial:
        command += ["-s", serial]
    command += args
    result = subprocess.run(
        command, capture_output=True, text=True, errors="replace", timeout=timeout
    )
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip() or "no output"
        raise AdbError(f"{' '.join(args)} failed: {detail}")
    return result.stdout


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


def interesting_log_lines(text: str, limit: int = 400) -> list[str]:
    """Keeps lines that name the projection stack, and drops exact duplicates.

    Duplicate detection ignores the leading timestamp so the same message
    repeated by a retry loop only shows up once.
    """
    seen: set[str] = set()
    kept: list[str] = []
    for line in text.splitlines():
        lowered = line.lower()
        if not any(keyword in lowered for keyword in LOG_KEYWORDS):
            continue
        key = TIMESTAMP_RE.sub("", line).strip()
        if key in seen:
            continue
        seen.add(key)
        kept.append(line)
        if len(kept) >= limit:
            break
    return kept


def capture_logcat(adb: str, serial: str, seconds: int) -> str:
    """Clears the buffer, waits, then dumps it (`-d` exits on its own)."""
    run_adb(adb, serial, ["logcat", "-c"])
    time.sleep(seconds)
    return run_adb(adb, serial, ["logcat", "-d", "-v", "time", "-b", "all"])


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# 手机侧日志采集",
        "",
        f"- 采集时间：{report['collected_at_utc']}",
        f"- 设备：{report['device_serial']}，记录 {report['seconds']} 秒",
        f"- 全量日志：`{report['log_path']}`（{report['log_lines']} 行）",
        f"- 命中：{report['hit_count']} 行（关键字：{'、'.join(LOG_KEYWORDS[:8])} …）",
        "",
        "## 命中行",
        "",
    ]
    if report["hits"]:
        for line in report["hits"]:
            lines.append(f"- `{line.strip()[:220]}`")
    else:
        lines.append("- 没有命中。确认采集期间手机正在投屏；也试试把关键字放宽。")
    lines += ["", "命中行里的端口号、编码参数、传输方式和错误信息，是补齐 `IccoaCodec` 的第一手线索。", ""]
    return "\n".join(lines)


FIXTURE_LOG = """07-12 10:00:01.100  1234  1234 I MiuiCarlink: discovery started, transport=wifi-p2p
07-12 10:00:01.200  1234  1234 I MiuiCarlink: discovery started, transport=wifi-p2p
07-12 10:00:02.300  1234  1234 D MediaCodec: configure 1920x720 60fps avc
07-12 10:00:02.400  1234  1234 E WifiP2pManager: group formed, GO=02:1a:2b:3c:4d:5e channel 149
07-12 10:00:02.500  9999  9999 I Sensors: accelerometer wakeup
07-12 10:00:03.600  1234  1234 I MiuiCarlink: connected to 192.168.49.1:7100
07-12 10:00:04.700  1234  1234 W SurfaceFlinger: dropped frame
"""


def selftest() -> int:
    hits = interesting_log_lines(FIXTURE_LOG)
    assert len(hits) == 4, hits
    joined = "\n".join(hits)
    assert "transport=wifi-p2p" in joined
    assert "192.168.49.1:7100" in joined
    assert "channel 149" in joined
    assert "accelerometer wakeup" not in joined
    assert "SurfaceFlinger" not in joined
    assert sum(1 for line in hits if "discovery started" in line) == 1, "duplicates must collapse"

    assert interesting_log_lines(FIXTURE_LOG, limit=2) == hits[:2]
    assert interesting_log_lines("07-12 10:00:00.000  1 1 I Other: nothing to see") == []

    report = {
        "collected_at_utc": "2026-10-10T02:00:00+00:00",
        "device_serial": "fixture",
        "seconds": 45,
        "log_path": "reports/phone-logcat.txt",
        "log_lines": len(FIXTURE_LOG.splitlines()),
        "hit_count": len(hits),
        "hits": hits,
    }
    markdown = render_markdown(report)
    assert "192.168.49.1:7100" in markdown
    assert "transport=wifi-p2p" in markdown
    print("selftest ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="ADB executable")
    parser.add_argument("--serial", help="ADB device serial")
    parser.add_argument("--seconds", type=int, default=45, help="how long to record")
    parser.add_argument("--report", type=Path, default=Path("reports/iccoa-phone-logs.md"))
    parser.add_argument("--log-out", type=Path, default=Path("reports/phone-logcat.txt"))
    parser.add_argument("--json", dest="json_path", type=Path, default=Path("reports/iccoa-phone-logs.json"))
    parser.add_argument("--limit", type=int, default=400)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    if args.seconds <= 0:
        parser.error("--seconds must be greater than zero")

    try:
        serial = choose_serial(args.adb, args.serial)
        print(f"clearing logcat, then recording for {args.seconds} s - start mirroring now")
        raw = capture_logcat(args.adb, serial, args.seconds)
    except (AdbError, subprocess.SubprocessError) as error:
        print(f"log capture failed: {error}", file=sys.stderr)
        return 1

    hits = interesting_log_lines(raw, args.limit)
    args.log_out.parent.mkdir(parents=True, exist_ok=True)
    args.log_out.write_text(raw, encoding="utf-8")
    report = {
        "collected_at_utc": dt.datetime.now(dt.UTC).isoformat(),
        "device_serial": serial,
        "seconds": args.seconds,
        "log_path": str(args.log_out),
        "log_lines": len(raw.splitlines()),
        "hit_count": len(hits),
        "hits": hits,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(render_markdown(report), encoding="utf-8")
    args.json_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"log lines: {report['log_lines']}  hits: {report['hit_count']}")
    for line in hits[:15]:
        print(f"- {line.strip()[:160]}")
    print(f"report: {args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
