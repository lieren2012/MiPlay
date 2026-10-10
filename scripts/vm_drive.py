#!/usr/bin/env python3
"""Drive the Android-x86 bench VM through VirtualBox's management interface.

This does not touch the Windows desktop: it reads the guest framebuffer with
`VBoxManage controlvm screenshotpng` and injects keystrokes with
`VBoxManage controlvm keyboardputscancode`. Both are VM management calls.

Why this exists: the Android-x86 ISO boots a VESA menu (vesamenu.c32) whose
default entry runs the setup wizard. We want the `nosetup` label instead, and
the menu only waits 60 seconds, so the keys have to go in at the right moment.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from vm_screen import VBOXMANAGE, decode_png  # noqa: E402

SCANCODES = {
    "esc": "01 81",
    "enter": "1c 9c",
    "tab": "0f 8f",
    "space": "39 b9",
    "backspace": "0e 8e",
    "up": "48 c8",
    "down": "50 d0",
    "left": "4b cb",
    "right": "4d cd",
    "end": "4f cf",
    "home": "47 c7",
}
for _i, _ch in enumerate("qwertyuiop"):
    SCANCODES[_ch] = f"{0x10 + _i:02x} {0x90 + _i:02x}"
for _i, _ch in enumerate("asdfghjkl"):
    SCANCODES[_ch] = f"{0x1e + _i:02x} {0x9e + _i:02x}"
for _i, _ch in enumerate("zxcvbnm"):
    SCANCODES[_ch] = f"{0x2c + _i:02x} {0xac + _i:02x}"
for _i, _ch in enumerate("1234567890"):
    SCANCODES[_ch] = f"{0x02 + _i:02x} {0x82 + _i:02x}"
SCANCODES.update({
    ".": "34 b4", "/": "35 b5", "-": "0c 8c", "=": "0d 8d", ",": "33 b3",
    ";": "27 a7", "[": "1a 9a", "]": "1b 9b", "\\": "2b ab", "`": "29 a9",
    "_": "0c 8c", ":": "27 a7",
})


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([VBOXMANAGE, *args], capture_output=True, text=True)


def vm_state(vm: str) -> str:
    result = run("showvminfo", vm, "--machinereadable")
    for line in result.stdout.splitlines():
        if line.startswith("VMState="):
            return line.split("=", 1)[1].strip('"')
    return "unknown"


def screenshot(vm: str, path: Path) -> tuple[int, int, list[list[int]]]:
    path.parent.mkdir(parents=True, exist_ok=True)
    result = run("controlvm", vm, "screenshotpng", str(path.resolve()))
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return decode_png(path.read_bytes())


def describe(width: int, height: int, rows: list[list[int]]) -> tuple[str, float]:
    flat = [v for row in rows for v in row]
    lit = sum(1 for v in flat if v > 40) / len(flat)
    if lit < 0.02:
        return "dark", lit
    if width >= 640 and lit > 0.70:
        return "graphical", lit
    if lit < 0.10:
        return "text-console", lit
    return "graphical-partial", lit


def send_keys(vm: str, sequence: list[str]) -> None:
    for key in sequence:
        scancodes = SCANCODES.get(key.lower())
        if scancodes is None:
            raise SystemExit(f"no scancode for {key!r}")
        run("controlvm", vm, "keyboardputscancode", *scancodes.split())


def send_text(vm: str, text: str) -> None:
    """Types text, wrapping uppercase letters in a shift press."""
    for ch in text:
        if ch == " ":
            send_keys(vm, ["space"])
            continue
        if ch.isupper() and ch.lower() in SCANCODES:
            basic = SCANCODES[ch.lower()].split()
            run("controlvm", vm, "keyboardputscancode", "2a", basic[0], basic[1], "aa")
        else:
            send_keys(vm, [ch])


def boot_live(vm: str, label: str, timeout: float, shot: Path) -> int:
    if vm_state(vm) != "running":
        print(f"starting {vm} ...")
        result = run("startvm", vm)
        if result.returncode != 0:
            print(f"startvm failed: {result.stderr.strip()}", file=sys.stderr)
            return 1

    started = time.time()
    menu_selected = False
    last_kind = ""
    while time.time() - started < timeout:
        time.sleep(3)
        try:
            width, height, rows = screenshot(vm, shot)
        except RuntimeError as error:
            print(f"  screenshot failed: {error}")
            continue
        kind, lit = describe(width, height, rows)
        elapsed = time.time() - started
        if kind != last_kind:
            print(f"  t={elapsed:5.1f}s  {width}x{height}  {kind}  lit={lit:.2%}")
            last_kind = kind

        if kind == "graphical" and not menu_selected:
            print(f"  menu detected -> sending Tab, Esc, {label!r}, Enter")
            send_keys(vm, ["tab"])
            time.sleep(1)
            send_keys(vm, ["esc"])
            time.sleep(1)
            send_text(vm, label)
            time.sleep(1)
            send_keys(vm, ["enter"])
            menu_selected = True
            print("  waiting for the guest to boot (this takes 1-3 minutes)")

        if menu_selected and elapsed > 150 and kind in ("graphical", "graphical-partial") and last_kind == kind:
            print("  screen has been graphical for a while; boot is probably underway")
            break
    print(f"final state: {describe(*screenshot(vm, shot)[:2], screenshot(vm, shot)[2])[0]}")
    return 0


def boot_nosetup_serial(vm: str, shot: Path, append: str, timeout: float) -> int:
    """Boots the Advanced -> "Live CD - No Setup Wizard" entry with a serial console."""
    if vm_state(vm) != "running":
        print(f"starting {vm} ...")
        result = run("startvm", vm)
        if result.returncode != 0:
            print(f"startvm failed: {result.stderr.strip()}", file=sys.stderr)
            return 1

    started = time.time()
    while time.time() - started < 90:
        time.sleep(3)
        try:
            width, height, rows = screenshot(vm, shot)
        except RuntimeError:
            continue
        kind, lit = describe(width, height, rows)
        if kind == "graphical" and lit > 0.7:
            print("menu up -> Advanced options -> No Setup Wizard -> append serial console")
            send_keys(vm, ["down", "down", "down"])
            time.sleep(0.8)
            send_keys(vm, ["enter"])
            time.sleep(1.2)
            send_keys(vm, ["down"])
            time.sleep(0.8)
            send_keys(vm, ["tab"])
            time.sleep(1.2)
            send_keys(vm, ["end"])
            time.sleep(0.6)
            send_text(vm, append)
            time.sleep(0.6)
            send_keys(vm, ["enter"])
            print("boot command entered")
            return 0
    print("menu never appeared", file=sys.stderr)
    return 1


def selftest() -> int:
    assert SCANCODES["enter"] == "1c 9c"
    assert SCANCODES["n"] == "31 b1"
    assert SCANCODES["o"] == "18 98"
    assert SCANCODES["s"] == "1f 9f"
    assert SCANCODES["e"] == "12 92"
    assert SCANCODES["t"] == "14 94"
    assert SCANCODES["u"] == "16 96"
    assert SCANCODES["p"] == "19 99"
    assert len(SCANCODES) >= 40, len(SCANCODES)
    print("selftest ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", default="boot", choices=["boot", "boot-nosetup", "keys", "type", "shot", "state"])
    parser.add_argument("--vm", default="AndroidX86-DiPlay")
    parser.add_argument("--label", default="nosetup")
    parser.add_argument("--timeout", type=float, default=240.0)
    parser.add_argument("--keys", help="space separated key names, e.g. 'esc down enter'")
    parser.add_argument("--text", help="text to type")
    parser.add_argument("--shot", type=Path, default=Path("reports/vm-screen.png"))
    parser.add_argument("--append", default=" console=ttyS0,115200")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    if args.action == "state":
        print(vm_state(args.vm))
        return 0
    if args.action == "shot":
        width, height, rows = screenshot(args.vm, args.shot)
        kind, lit = describe(width, height, rows)
        print(f"{width}x{height} {kind} lit={lit:.2%}")
        return 0
    if args.action == "boot-nosetup":
        return boot_nosetup_serial(args.vm, args.shot, args.append, args.timeout)
    if args.action == "keys":
        if not args.keys:
            parser.error("--keys is required")
        send_keys(args.vm, args.keys.split())
        print(f"sent: {args.keys}")
        return 0
    if args.action == "type":
        if not args.text:
            parser.error("--text is required")
        send_text(args.vm, args.text)
        print(f"typed: {args.text}")
        return 0
    return boot_live(args.vm, args.label, args.timeout, args.shot)


if __name__ == "__main__":
    raise SystemExit(main())
