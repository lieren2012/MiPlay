#!/usr/bin/env python3
"""Non-invasive probe of the ICCOA -> CarPlay box.

The box (车连易 / Carlinkit CPC200-U2H IE in this project) is the Wi-Fi access
point and serves a web management page. This script only reads what the box
already offers on its own LAN:

  * which TCP ports are listening, and what banner they greet with
  * what the web page says (server header, title, links, scripts)
  * whether the page or its scripts mention the protocol/upgrade/log surfaces
    that decide how the session can be captured

It does not brute-force credentials, does not upload anything, does not touch
storage or firmware. If the web page needs a password, pass the one you already
have with --user/--password.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import http.client
import json
import re
import socket
import ssl
import subprocess
import sys
from pathlib import Path
from typing import Any

PORT_SERVICES = {
    21: "ftp",
    22: "ssh",
    23: "telnet",
    53: "dns",
    80: "http",
    443: "https",
    554: "rtsp",
    1883: "mqtt",
    5000: "upnp/http-alt",
    5555: "adb-tcp",
    7000: "airplay",
    7100: "airplay-ctrl",
    8000: "http-alt",
    8080: "http-alt",
    8081: "http-alt",
    8088: "http-alt",
    8443: "https-alt",
    8888: "http-alt",
    9000: "http-alt",
    9999: "http-alt",
}

HTTP_PORTS = (80, 443, 8000, 8080, 8081, 8088, 8443, 8888, 9000, 9999)

INTERESTING_TOKENS = (
    "carlink",
    "carlife",
    "carwith",
    "carplay",
    "iccoa",
    "hicar",
    "autolink",
    "cgi-bin",
    "/api",
    "ws://",
    "wss://",
    "upgrade",
    "firmware",
    "version",
    "debug",
    "syslog",
    "log_download",
    "ssid",
    "psk",
)

VERSION_RE = re.compile(r"(?:version|fw|firmware|sw|build)[^0-9]{0,12}(\d+\.\d+(?:\.\d+){0,2})", re.I)
TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.I | re.S)
LINK_RE = re.compile(r"""(?:href|src)\s*=\s*["']([^"'>]+)["']""", re.I)
TOKEN_MAX_BYTES = 512 * 1024


class ProbeError(RuntimeError):
    pass


def port_open(host: str, port: int, timeout: float) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def read_banner(host: str, port: int, timeout: float) -> str:
    try:
        with socket.create_connection((host, port), timeout=timeout) as connection:
            connection.settimeout(timeout)
            try:
                data = connection.recv(128)
            except (socket.timeout, OSError):
                return ""
        return data.decode("utf-8", "replace").strip()
    except OSError:
        return ""


def probe_ports(host: str, ports: dict[int, str], timeout: float, workers: int) -> list[dict[str, Any]]:
    found: list[dict[str, Any]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(port_open, host, port, timeout): port for port in ports}
        for future, port in futures.items():
            try:
                is_open = future.result()
            except Exception:
                is_open = False
            if not is_open:
                continue
            found.append(
                {
                    "port": port,
                    "service": ports[port],
                    "banner": read_banner(host, port, timeout) if port in (21, 22, 23) else "",
                }
            )
    return sorted(found, key=lambda item: item["port"])


class HttpResponse:
    __slots__ = ("port", "scheme", "status", "headers", "body")

    def __init__(self, port: int, scheme: str, status: int, headers: dict[str, str], body: bytes) -> None:
        self.port = port
        self.scheme = scheme
        self.status = status
        self.headers = headers
        self.body = body


def fetch(host: str, port: int, path: str, timeout: float, auth: tuple[str, str] | None) -> HttpResponse:
    scheme = "https" if port in (443, 8443) else "http"
    if scheme == "https":
        context = ssl._create_unverified_context()
        connection: http.client.HTTPConnection = http.client.HTTPSConnection(
            host, port, timeout=timeout, context=context
        )
    else:
        connection = http.client.HTTPConnection(host, port, timeout=timeout)

    headers = {"User-Agent": "iccoa-box-probe", "Accept": "*/*"}
    if auth:
        import base64

        token = base64.b64encode(f"{auth[0]}:{auth[1]}".encode()).decode()
        headers["Authorization"] = f"Basic {token}"
    try:
        connection.request("GET", path, headers=headers)
        response = connection.getresponse()
        body = response.read(TOKEN_MAX_BYTES)
        header_map = {key.lower(): value for key, value in response.getheaders()}
        return HttpResponse(port, scheme, response.status, header_map, body)
    finally:
        connection.close()


def decode_body(body: bytes, content_type: str | None) -> str:
    """Decodes a web page body, honouring the declared charset.

    Embedded Chinese web UIs on these boxes are often GBK-family rather than
    UTF-8, so the declared charset comes first and gb18030 is tried before
    giving up.
    """
    candidates: list[str] = []
    if content_type:
        declared = re.search(r"charset\s*=\s*[\"\x27]?([\w-]+)", content_type, re.I)
        if declared:
            candidates.append(declared.group(1))
    in_body = re.search(rb"charset\s*=\s*[\"\x27]?([\w-]+)", body[:4096], re.I)
    if in_body:
        candidates.append(in_body.group(1).decode("ascii", "replace"))
    candidates += ["utf-8", "gb18030", "latin-1"]
    for encoding in candidates:
        try:
            return body.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return body.decode("utf-8", "replace")


def extract_title(text: str) -> str | None:
    match = TITLE_RE.search(text)
    if not match:
        return None
    return re.sub(r"\s+", " ", match.group(1)).strip() or None


def extract_links(text: str) -> list[str]:
    links: list[str] = []
    for raw in LINK_RE.findall(text):
        candidate = raw.strip()
        if candidate.startswith(("data:", "javascript:", "#")):
            continue
        if candidate not in links:
            links.append(candidate)
    return links


def scan_tokens(text: str) -> dict[str, int]:
    lowered = text.lower()
    hits = {token: lowered.count(token) for token in INTERESTING_TOKENS if token in lowered}
    return dict(sorted(hits.items(), key=lambda item: item[1], reverse=True))


def find_versions(text: str) -> list[str]:
    return sorted({match.group(1) for match in VERSION_RE.finditer(text)})


def probe_web(
    host: str,
    open_ports: list[dict[str, Any]],
    timeout: float,
    auth: tuple[str, str] | None,
    http_ports: tuple[int, ...] = HTTP_PORTS,
) -> list[dict[str, Any]]:
    pages: list[dict[str, Any]] = []
    for entry in open_ports:
        if entry["port"] not in http_ports:
            continue
        try:
            response = fetch(host, entry["port"], "/", timeout, auth)
        except Exception as error:  # noqa: BLE001 - any transport failure is just "not a web page"
            pages.append({"port": entry["port"], "error": str(error)})
            continue
        text = decode_body(response.body, response.headers.get("content-type"))
        pages.append(
            {
                "port": entry["port"],
                "scheme": response.scheme,
                "status": response.status,
                "server": response.headers.get("server"),
                "title": extract_title(text),
                "links": extract_links(text),
                "versions": find_versions(text),
                "tokens": scan_tokens(text),
                "bytes": len(response.body),
                "body": text,
            }
        )
    return pages


def save_pages(host: str, pages: list[dict[str, Any]], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    for page in pages:
        if "body" not in page:
            continue
        name = f"{host.replace('.', '_')}_{page['port']}_index.html"
        (out_dir / name).write_text(page["body"], encoding="utf-8")


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# ICCOA 盒子探测报告",
        "",
        f"- 目标：`{report['host']}`",
        f"- 端口探测：{report['port_timeout_s']} s 超时",
        "",
        "## 开放端口",
        "",
    ]
    if report["open_ports"]:
        lines.append("| 端口 | 猜测服务 | 横幅 |")
        lines.append("| --- | --- | --- |")
        for entry in report["open_ports"]:
            banner = (entry["banner"] or "").replace("\n", " ")[:60]
            lines.append(f"| {entry['port']} | {entry['service']} | `{banner}` |")
    else:
        lines.append("- 没有开放端口（确认已连上盒子的热点）")
    lines += ["", "## Web 页面", ""]
    for page in report["web"]:
        if "error" in page:
            lines.append(f"- 端口 {page['port']}：非 HTTP 或不可读（{page['error']}）")
            continue
        lines.append(f"### 端口 {page['port']} ({page['scheme']})")
        lines.append("")
        lines.append(f"- 状态：{page['status']}，Server：{page['server']}")
        lines.append(f"- 标题：{page['title']}")
        if page["versions"]:
            lines.append(f"- 版本号线索：{', '.join(page['versions'])}")
        if page["tokens"]:
            rendered = ", ".join(f"`{token}`×{count}" for token, count in list(page["tokens"].items())[:12])
            lines.append(f"- 关键字命中：{rendered}")
        if page["links"]:
            lines.append(f"- 链接：{', '.join(page['links'][:12])}")
        lines.append("")
    lines += ["## 由探测结果决定的采集路径", ""]
    for step in report["next_steps"]:
        lines.append(f"1. {step}")
    lines += ["", "保存下来的 HTML 在 `reports/box-web/`，可以直接用浏览器打开看。", ""]
    return "\n".join(lines)


def next_steps(report: dict[str, Any]) -> list[str]:
    ports = {entry["port"] for entry in report["open_ports"]}
    steps: list[str] = []
    if 5555 in ports:
        steps.append("盒子开了 adb：`adb connect <盒子IP>:5555` 成功后，在盒子上 `tcpdump -i any -s 0 -w /tmp/iccoa.pcap`（最优路径）")
    if 22 in ports:
        steps.append("盒子开了 ssh：登录后同样在盒子上抓包，并顺手保存 `logread`/`dmesg`")
    if 23 in ports:
        steps.append("盒子开了 telnet：登录后同样在盒子上抓包")
    web = [page for page in report["web"] if "error" not in page]
    if web:
        steps.append("用浏览器打开盒子 Web 页，找到 **WLAN/热点设置**，抄下 SSID 与 PSK（监听模式解密必须用到）")
        steps.append("在 Web 页里找 **系统升级/关于/日志** 页，记下固件版本与日期；只读取，不要下载或解包固件")
    steps.append("无论以上是否成功：把笔记本连到盒子热点，抓组播发现流量 `tcpdump -i <网卡> -w discovery.pcap 'port 5353 or port 1900'`，这一步不需要 PSK")
    steps.append("如果有 PSK：在盒子所在信道开监听模式，从关联之前开始抓，再用 Wireshark 的 wpa-pwd 解密")
    return steps


def resolve_host(args: argparse.Namespace) -> str:
    if args.host:
        return args.host
    if args.from_recon and args.from_recon.exists():
        report = json.loads(args.from_recon.read_text(encoding="utf-8"))
        gateway = report.get("network", {}).get("gateway")
        if gateway:
            print(f"using gateway {gateway} from {args.from_recon}")
            return gateway
    try:
        output = subprocess.run(
            [args.adb, "shell", "ip", "route"],
            capture_output=True,
            text=True,
            timeout=15,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        output = ""
    for line in output.splitlines():
        if line.startswith("default ") and "via" in line:
            return line.split("via")[1].split()[0]
    raise ProbeError("cannot determine the box address; pass --host or run iccoa_recon.py first")


def selftest() -> int:
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    body = (
        b"<html><head><title>CarLinkIt CPC200</title></head><body>"
        b"<a href='/wlan.html'>WLAN</a><a href='/upgrade.html'>Upgrade</a>"
        b"<script src='/main.js'></script><p>Firmware version 1.2.3</p>"
        b"<p>SSID: C2W-1234</p></body></html>"
    )

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - http.server API
            self.send_response(200)
            self.send_header("Server", "boa/0.94.14rc21")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args: Any) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    port = server.server_address[1]
    try:
        assert port_open("127.0.0.1", port, 1.0) is True
        assert port_open("127.0.0.1", port + 1, 0.3) is False

        response = fetch("127.0.0.1", port, "/", 2.0, None)
        text = response.body.decode("utf-8")
        assert response.status == 200
        assert response.headers["server"] == "boa/0.94.14rc21"
        assert extract_title(text) == "CarLinkIt CPC200"
        links = extract_links(text)
        assert "/wlan.html" in links and "/main.js" in links, links
        assert find_versions(text) == ["1.2.3"], find_versions(text)
        assert "ssid" in scan_tokens(text), scan_tokens(text)

        report = {
            "host": "127.0.0.1",
            "port_timeout_s": 1.0,
            "open_ports": [{"port": port, "service": "http", "banner": ""}],
            "web": probe_web(
                "127.0.0.1",
                [{"port": port, "service": "http", "banner": ""}],
                2.0,
                None,
                http_ports=(port,),
            ),
        }
        report["web"][0].pop("body")
        report["next_steps"] = next_steps(report)
        markdown = render_markdown(report)
        assert "CarLinkIt CPC200" in markdown
        assert "1.2.3" in markdown
        assert any("5353" in step for step in report["next_steps"])
    finally:
        server.shutdown()
        server.server_close()

    print("selftest ok")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", help="box address; defaults to the gateway from --from-recon or adb")
    parser.add_argument("--from-recon", type=Path, default=Path("reports/iccoa-recon.json"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--user", help="web UI user name, if the page asks for one")
    parser.add_argument("--password", help="web UI password")
    parser.add_argument("--timeout", type=float, default=0.8, help="per-port connect timeout in seconds")
    parser.add_argument("--workers", type=int, default=32)
    parser.add_argument("--out-dir", type=Path, default=Path("reports/box-web"))
    parser.add_argument("--report", type=Path, default=Path("reports/iccoa-box-probe.md"))
    parser.add_argument("--json", dest="json_path", type=Path, default=Path("reports/iccoa-box-probe.json"))
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    try:
        host = resolve_host(args)
    except ProbeError as error:
        print(f"probe failed: {error}", file=sys.stderr)
        return 1

    auth = (args.user, args.password) if args.user and args.password else None
    print(f"probing {host} on {len(PORT_SERVICES)} ports ...")
    open_ports = probe_ports(host, PORT_SERVICES, args.timeout, args.workers)
    web = probe_web(host, open_ports, max(args.timeout * 4, 3.0), auth)
    save_pages(host, web, args.out_dir)

    report = {
        "host": host,
        "port_timeout_s": args.timeout,
        "open_ports": open_ports,
        "web": [{key: value for key, value in page.items() if key != "body"} for page in web],
    }
    report["next_steps"] = next_steps(report)

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(render_markdown(report), encoding="utf-8")
    args.json_path.parent.mkdir(parents=True, exist_ok=True)
    args.json_path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"open ports: {[entry['port'] for entry in open_ports]}")
    for page in web:
        if "error" in page:
            print(f"- {page['port']}: not http ({page['error']})")
        else:
            print(f"- {page['port']}: {page['title']!r} server={page['server']!r} versions={page['versions']}")
    print(f"report: {args.report}")
    print("next steps are in the report; start with the multicast discovery capture, it needs no key")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
