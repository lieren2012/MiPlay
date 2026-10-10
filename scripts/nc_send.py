#!/usr/bin/env python3
"""One-shot TCP file sender for guests that only have netcat.

The bench VM reaches the host at 10.0.2.2 through VirtualBox NAT (which maps to
the host loopback), so this listens on 127.0.0.1 and the guest pulls with
`nc 10.0.2.2 <port> > <path>`.
"""

from __future__ import annotations

import argparse
import hashlib
import socket
import sys
import time
from pathlib import Path


def send_once(path: Path, port: int, timeout: float) -> int:
    payload = path.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    print(f"serving {path.name} ({len(payload)} bytes, sha256 {digest[:16]}...) on 127.0.0.1:{port}")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", port))
        server.listen(1)
        server.settimeout(timeout)
        try:
            connection, address = server.accept()
        except socket.timeout:
            print("no client connected in time", file=sys.stderr)
            return 1
        print(f"client {address[0]} connected, sending ...")
        connection.sendall(payload)
        connection.shutdown(socket.SHUT_WR)
        connection.close()
    print(f"sent {len(payload)} bytes")
    return 0


def selftest() -> int:
    import threading, tempfile

    with tempfile.TemporaryDirectory() as tmp:
        sample = Path(tmp) / "sample.bin"
        sample.write_bytes(b"bench-payload" * 100)
        result: list[int] = []

        def worker() -> None:
            result.append(send_once(sample, 18899, 10))

        thread = threading.Thread(target=worker)
        thread.start()
        time.sleep(0.4)
        with socket.create_connection(("127.0.0.1", 18899), timeout=5) as client:
            received = b""
            while True:
                chunk = client.recv(4096)
                if not chunk:
                    break
                received += chunk
        thread.join()
        assert received == sample.read_bytes(), "payload mismatch"
        assert result == [0], result
    print("selftest ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, help="file to send")
    parser.add_argument("--port", type=int, default=8888)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()
    if not args.file:
        parser.error("--file is required unless --selftest is used")
    return send_once(args.file, args.port, args.timeout)


if __name__ == "__main__":
    raise SystemExit(main())
