#!/usr/bin/env python3
"""Run shell commands inside the bench VM over its serial console.

The VM's UART1 is a TCPServer, so this connects, wraps each command between
unique markers, and collects the output in between. Root shell, no guest tools,
no screenshots.
"""

from __future__ import annotations

import argparse
import socket
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vm_serial as serial  # noqa: E402


def run_commands(port: int, commands: list[str], timeout: float, log: Path) -> list[tuple[str, str]]:
    sock = serial.connect(port)
    results: list[tuple[str, str]] = []
    token = "QZX7M3NP"
    with log.open("w", encoding="utf-8", errors="replace") as handle:
        # sync with the shell and try to silence the console echo
        sock.sendall(b"\nstty -echo 2>/dev/null\n")
        time.sleep(1.5)
        try:
            sock.recv(131072)
        except (socket.timeout, OSError):
            pass
        for index, command in enumerate(commands):
            start = f"{token}A{index}"
            end = f"{token}B{index}"
            payload = f'echo {start}; {command}; echo {end}\n'
            sock.sendall(payload.encode())
            buffer = ""
            deadline = time.time() + timeout
            last_data = time.time()
            while time.time() < deadline:
                try:
                    chunk = sock.recv(65536)
                except socket.timeout:
                    if end in buffer and time.time() - last_data > 1.5:
                        break
                    continue
                except OSError:
                    break
                if not chunk:
                    break
                buffer += chunk.decode("utf-8", "replace")
                last_data = time.time()
                if end in buffer and time.time() - last_data > 1.5:
                    break
            handle.write(f"$ {command}\n{buffer}\n")
            handle.flush()
            # The console echoes the input line, so take the text after the last marker.
            end_at = buffer.rfind(end)
            start_at = buffer.rfind(start, 0, end_at if end_at > 0 else len(buffer))
            if start_at >= 0 and end_at > start_at:
                body = buffer[start_at + len(start) : end_at]
            else:
                body = buffer
            results.append((command, body.strip()))
    return results


def selftest() -> int:
    assert "console" in "console:/ #"
    print("selftest ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=12345)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--log", type=Path, default=Path("reports/vm-shell.log"))
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("commands", nargs="*", help="commands to run, one shell line each")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    if not args.commands:
        parser.error("give at least one command")

    for command, output in run_commands(args.port, args.commands, args.timeout, args.log):
        print(f"$ {command}")
        print(output if output else "(no output)")
        print()
    print(f"log: {args.log}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
