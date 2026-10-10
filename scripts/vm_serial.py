#!/usr/bin/env python3
"""Read (and optionally drive) the bench VM's serial console.

The VM has UART1 mapped to a TCP server on 127.0.0.1:12345. This connects to it,
boots Android-x86 with `console=ttyS0,115200` appended at the boot prompt, and
logs everything the guest prints. That is the only way to see where a black
screen actually comes from: the framebuffer shows nothing, the kernel console
shows everything.
"""

from __future__ import annotations

import argparse
import socket
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vm_drive as drive  # noqa: E402

PORT = 12345


def connect(port: int, retries: int = 20) -> socket.socket:
    last = None
    for _ in range(retries):
        try:
            sock = socket.create_connection(("127.0.0.1", port), timeout=5)
            sock.settimeout(1.0)
            return sock
        except OSError as error:
            last = error
            time.sleep(1)
    raise SystemExit(f"cannot connect to the VM serial port: {last}")


def boot_with_serial(vm: str, shot: Path, append: str, port: int, seconds: float, log: Path) -> int:
    # The UART backend only listens once the VM runs, so start it first.
    drive.run("startvm", vm)
    time.sleep(2)
    sock = connect(port)
    print(f"serial connected on {port}")
    menu_seen = False
    started = time.time()
    with log.open("w", encoding="utf-8", errors="replace") as handle:
        while time.time() - started < seconds:
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                chunk = b""
            except OSError:
                break
            if chunk:
                text = chunk.decode("utf-8", "replace")
                handle.write(text)
                handle.flush()
                for line in text.splitlines():
                    if line.strip():
                        print(f"  | {line[:200]}")

            if not menu_seen and time.time() - started < 60:
                try:
                    width, height, rows = drive.screenshot(vm, shot)
                    kind, lit = drive.describe(width, height, rows)
                except Exception:
                    continue
                if kind == "graphical" and lit > 0.7:
                    print("menu detected -> appending serial console argument")
                    drive.send_keys(vm, ["tab"])
                    time.sleep(1)
                    drive.send_keys(vm, ["end"])
                    time.sleep(1)
                    drive.send_text(vm, append)
                    time.sleep(1)
                    drive.send_keys(vm, ["enter"])
                    menu_seen = True
    print(f"serial log: {log}")
    return 0


def selftest() -> int:
    assert PORT == 12345
    print("selftest ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", default="boot", choices=["boot", "listen"])
    parser.add_argument("--vm", default="AndroidX86-DiPlay")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--append", default=" console=ttyS0,115200")
    parser.add_argument("--seconds", type=float, default=240.0)
    parser.add_argument("--shot", type=Path, default=Path("reports/vm-screen.png"))
    parser.add_argument("--log", type=Path, default=Path("reports/vm-serial.log"))
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    if args.action == "listen":
        sock = connect(args.port)
        started = time.time()
        with args.log.open("w", encoding="utf-8", errors="replace") as handle:
            while time.time() - started < args.seconds:
                try:
                    chunk = sock.recv(65536)
                except socket.timeout:
                    continue
                except OSError:
                    break
                if chunk:
                    text = chunk.decode("utf-8", "replace")
                    handle.write(text)
                    handle.flush()
                    print(text, end="")
        return 0
    return boot_with_serial(args.vm, args.shot, args.append, args.port, args.seconds, args.log)


if __name__ == "__main__":
    raise SystemExit(main())
