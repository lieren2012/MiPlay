#!/usr/bin/env python3
"""Collect non-invasive Android and CarLife information through ADB."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

PACKAGE_KEYWORDS = ("carlife", "car", "drive", "auto")
PROPERTY_KEYS = (
    "ro.product.manufacturer",
    "ro.product.brand",
    "ro.product.model",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.build.version.security_patch",
    "ro.build.display.id",
)
PACKAGE_RE = re.compile(r"^package:([A-Za-z0-9._]+)$")


class AdbError(RuntimeError):
    pass


def run_adb(adb: str, serial: str | None, arguments: list[str]) -> str:
    command = [adb]
    if serial:
        command.extend(["-s", serial])
    command.extend(arguments)
    try:
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=20,
        )
    except FileNotFoundError as error:
        raise AdbError(f"ADB executable not found: {adb}") from error
    except subprocess.TimeoutExpired as error:
        raise AdbError(f"ADB command timed out: {' '.join(arguments)}") from error
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "unknown error"
        raise AdbError(f"ADB command failed: {' '.join(arguments)}: {detail}")
    return result.stdout


def choose_serial(adb: str, requested: str | None) -> str:
    output = run_adb(adb, None, ["devices", "-l"])
    devices: list[str] = []
    for line in output.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            devices.append(fields[0])
    if requested:
        if requested not in devices:
            raise AdbError(f"requested device {requested} is not connected and authorized")
        return requested
    if not devices:
        raise AdbError("no authorized Android device found; enable USB debugging")
    if len(devices) > 1:
        raise AdbError("multiple Android devices found; pass --serial explicitly")
    return devices[0]


def collect_properties(adb: str, serial: str) -> dict[str, str]:
    properties: dict[str, str] = {}
    for key in PROPERTY_KEYS:
        value = run_adb(adb, serial, ["shell", "getprop", key]).strip()
        properties[key] = value or "unknown"
    return properties


def collect_packages(adb: str, serial: str) -> list[dict[str, Any]]:
    output = run_adb(adb, serial, ["shell", "pm", "list", "packages"])
    package_names: set[str] = set()
    for raw_line in output.splitlines():
        match = PACKAGE_RE.match(raw_line.strip())
        if not match:
            continue
        package = match.group(1)
        lowered = package.lower()
        if any(keyword in lowered for keyword in PACKAGE_KEYWORDS):
            package_names.add(package)

    packages: list[dict[str, Any]] = []
    for package in sorted(package_names):
        details = run_adb(adb, serial, ["shell", "dumpsys", "package", package])
        version_name = "unknown"
        version_code = "unknown"
        version_match = re.search(r"versionName=([^\s]+)", details)
        if version_match:
            version_name = version_match.group(1)
        code_match = re.search(r"versionCode=(\d+)", details)
        if code_match:
            version_code = code_match.group(1)
        bucket = run_adb(adb, serial, ["shell", "am", "get-standby-bucket", package]).strip()
        packages.append(
            {
                "package": package,
                "version_name": version_name,
                "version_code": version_code,
                "standby_bucket": bucket or "unknown",
            }
        )
    return packages


def collect_battery_whitelist(adb: str, serial: str) -> list[str]:
    try:
        output = run_adb(adb, serial, ["shell", "dumpsys", "deviceidle", "whitelist"])
    except AdbError:
        return []
    relevant = []
    for line in output.splitlines():
        value = line.strip()
        if any(keyword in value.lower() for keyword in PACKAGE_KEYWORDS):
            relevant.append(value)
    return relevant


def collect(adb: str, serial: str) -> dict[str, Any]:
    return {
        "collected_at_utc": dt.datetime.now(dt.UTC).isoformat(),
        "device_serial": serial,
        "properties": collect_properties(adb, serial),
        "candidate_packages": collect_packages(adb, serial),
        "battery_whitelist": collect_battery_whitelist(adb, serial),
        "wireless_carlife_verified": False,
        "notes": [
            "ADB package presence does not prove wireless CarLife connectivity.",
            "CarPlay resolution and head-unit capabilities require a CarPlay session capture.",
        ],
    }


def write_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb", help="ADB executable")
    parser.add_argument("--serial", help="ADB device serial")
    parser.add_argument("--report", type=Path, default=Path("reports/phone-info.json"))
    args = parser.parse_args()

    try:
        serial = choose_serial(args.adb, args.serial)
        report = collect(args.adb, serial)
        write_report(args.report, report)
    except AdbError as error:
        print(f"phone collection failed: {error}", file=sys.stderr)
        return 1

    print(f"device: {report['properties'].get('ro.product.model', 'unknown')}")
    print(f"Android: {report['properties'].get('ro.build.version.release', 'unknown')}")
    print(f"security patch: {report['properties'].get('ro.build.version.security_patch', 'unknown')}")
    print(f"candidate packages: {len(report['candidate_packages'])}")
    for package in report["candidate_packages"]:
        print(f"- {package['package']} {package['version_name']} ({package['standby_bucket']})")
    print(f"report: {args.report}")
    print("wireless CarLife remains unverified until a real session succeeds")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
