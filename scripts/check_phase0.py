#!/usr/bin/env python3
"""Validate phase-0 vehicle, phone, and clean-room inputs."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise ValueError(f"{path}: file does not exist") from None
    except json.JSONDecodeError as error:
        raise ValueError(f"{path}: invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise ValueError(f"{path}: top-level value must be an object")
    return value


def require_text(errors: list[str], label: str, value: Any) -> None:
    if not isinstance(value, str) or not value.strip() or value.strip().lower() in {"unknown", "tbd"}:
        errors.append(f"{label}: must be filled with a known value")


def validate_vehicle(data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    for key in ("vehicle_name", "market", "head_unit_software", "carplay_version", "usb_port"):
        require_text(errors, f"vehicle.{key}", data.get(key))

    display = data.get("display")
    if not isinstance(display, dict):
        errors.append("vehicle.display: must be an object")
    else:
        if display.get("measured") is not True:
            errors.append("vehicle.display.measured: must be true")
        width = display.get("width")
        height = display.get("height")
        if not isinstance(width, int) or not isinstance(height, int) or width <= 0 or height <= 0:
            errors.append("vehicle.display: width and height must be positive integers")
        elif width * height > 1920 * 720:
            errors.append("vehicle.display: exceeds the v1 1920x720 pixel budget")

    max_fps = data.get("max_fps")
    if not isinstance(max_fps, int) or not 1 <= max_fps <= 60:
        errors.append("vehicle.max_fps: must be an integer from 1 through 60")

    capabilities = data.get("capabilities")
    if not isinstance(capabilities, dict):
        errors.append("vehicle.capabilities: must be an object")
    else:
        for key in ("touch", "knob", "steering_wheel", "microphone", "call_audio", "navigation_audio"):
            if capabilities.get(key) is not True:
                errors.append(f"vehicle.capabilities.{key}: must be true")

    power = data.get("power")
    if not isinstance(power, dict):
        errors.append("vehicle.power: must be an object")
    else:
        for key in ("port_voltage", "current_limit", "ignition_behavior"):
            require_text(errors, f"vehicle.power.{key}", power.get(key))
        reported_w = power.get("reported_max_output_w")
        if not isinstance(reported_w, int) or reported_w <= 0:
            errors.append("vehicle.power.reported_max_output_w: must be a positive integer")
        if power.get("pd_capability_verified") is not True:
            errors.append("vehicle.power.pd_capability_verified: USB PD behavior must be measured")
    return errors


def validate_phone(data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    for key in (
        "manufacturer",
        "model",
        "android_version",
        "security_patch",
        "carlife_version",
        "connection_method",
        "background_restrictions",
    ):
        require_text(errors, f"phone.{key}", data.get(key))
    if data.get("wireless_carlife") is not True:
        errors.append("phone.wireless_carlife: must be verified true")
    if data.get("root_or_os_modification_required") is not False:
        errors.append("phone.root_or_os_modification_required: must be false")
    return errors


def validate_license(data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    checks = (
        ("clean_room", True),
        ("black_box_reference_allowed", True),
        ("unlicensed_source_reuse", False),
        ("firmware_redistribution", False),
        ("authentication_bypass", False),
    )
    for key, expected in checks:
        if data.get(key) is not expected:
            errors.append(f"license.{key}: must be {str(expected).lower()}")
    if not isinstance(data.get("third_party_components"), list):
        errors.append("license.third_party_components: must be an array")
    return errors


def run(vehicle_path: Path, phone_path: Path, license_path: Path) -> list[str]:
    errors: list[str] = []
    for validator, path in (
        (validate_vehicle, vehicle_path),
        (validate_phone, phone_path),
        (validate_license, license_path),
    ):
        try:
            errors.extend(validator(load_json(path)))
        except ValueError as error:
            errors.append(str(error))
    return errors


def self_test() -> None:
    vehicle = {
        "vehicle_name": "test",
        "market": "test",
        "head_unit_software": "1",
        "carplay_version": "1",
        "usb_port": "USB-A",
        "display": {"width": 1920, "height": 720, "measured": True},
        "max_fps": 60,
        "capabilities": {
            "touch": True,
            "knob": True,
            "steering_wheel": True,
            "microphone": True,
            "call_audio": True,
            "navigation_audio": True,
        },
        "power": {
            "port_voltage": "5V",
            "current_limit": "1A",
            "ignition_behavior": "switched",
            "reported_max_output_w": 50,
            "pd_capability_verified": True,
        },
    }
    phone = {
        "manufacturer": "test",
        "model": "test",
        "android_version": "14",
        "security_patch": "2026-01",
        "carlife_version": "8",
        "connection_method": "Wi-Fi",
        "wireless_carlife": True,
        "background_restrictions": "verified",
        "root_or_os_modification_required": False,
    }
    license_data = {
        "clean_room": True,
        "black_box_reference_allowed": True,
        "unlicensed_source_reuse": False,
        "firmware_redistribution": False,
        "authentication_bypass": False,
        "third_party_components": [],
    }
    errors = validate_vehicle(vehicle) + validate_phone(phone) + validate_license(license_data)
    if errors:
        raise SystemExit(f"self-test failed: {errors}")
    print("phase-0 checker self-test: PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vehicle", type=Path, default=Path("config/vehicle.lacrosse-2025.json"))
    parser.add_argument("--phone", type=Path, default=Path("config/phone.primary.json"))
    parser.add_argument("--license", type=Path, default=Path("config/license-boundary.json"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return 0

    errors = run(args.vehicle, args.phone, args.license)
    if errors:
        print("phase 0: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("phase 0: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
