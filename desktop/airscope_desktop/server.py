"""
Local web UI: serves the same hud.html the phone serves (so the laptop looks like the phone's HUD) and the
/api/state + /api/map JSON it expects. In mirror mode the JSON comes from the phone instead of the local scan.
Binds to 127.0.0.1 only unless asked otherwise; nothing leaves the machine except, in mirror mode, requests to
the phone's own HUD address you typed in.
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable, Optional
from urllib.parse import parse_qs, urlsplit


def hud_html_path() -> Optional[str]:
    """hud.html: bundled next to the executable (PyInstaller --add-data) or from the repo checkout."""
    here = os.path.dirname(os.path.abspath(__file__))
    for p in (
        os.path.join(getattr(sys, "_MEIPASS", ""), "hud.html") if getattr(sys, "_MEIPASS", "") else "",
        os.path.join(here, "hud.html"),
        os.path.join(here, "..", "..", "app", "src", "main", "assets", "hud.html"),
    ):
        if p and os.path.isfile(p):
            return p
    return None


class HudState:
    """What the server hands out; the scan loop replaces these strings, the handler only reads them."""

    def __init__(self) -> None:
        self.state_json = '{"time":0,"scanning":false,"warning":"starting","env":"Home / indoor","alpr":"","mapStatus":"","mapV":0,"me":null,"devices":[],"timeline":[]}'
        self.map_json = '{"lat":null}'
        self.mirror: Optional[str] = None  # e.g. http://192.168.1.20:8787/?k=abc


def fetch_mirror(base: str, path: str, timeout: float = 4.0) -> bytes:
    """GET /api/<path> from the phone's HUD URL (http://ip:8787/?k=token)."""
    u = urlsplit(base)
    token = parse_qs(u.query).get("k", [""])[0]
    url = f"{u.scheme}://{u.netloc}/api/{path}?k={token}"
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read()


def make_handler(state: HudState, page: bytes) -> type:
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # quiet
            pass

        def _send(self, code: int, ctype: str, body: bytes) -> None:
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:
            path = urlsplit(self.path).path
            try:
                if path in ("/", "/index.html"):
                    self._send(200, "text/html; charset=utf-8", page)
                elif path == "/api/state":
                    if state.mirror:
                        self._send(200, "application/json; charset=utf-8", fetch_mirror(state.mirror, "state"))
                    else:
                        self._send(200, "application/json; charset=utf-8", state.state_json.encode("utf-8"))
                elif path == "/api/map":
                    if state.mirror:
                        self._send(200, "application/json; charset=utf-8", fetch_mirror(state.mirror, "map", 20.0))
                    else:
                        self._send(200, "application/json; charset=utf-8", state.map_json.encode("utf-8"))
                elif path == "/favicon.ico":
                    self._send(204, "image/x-icon", b"")
                else:
                    self._send(404, "text/plain", b"not found")
            except Exception as e:  # mirror unreachable etc.
                self._send(502, "application/json; charset=utf-8", json.dumps({"error": str(e)}).encode("utf-8"))

    return Handler


def serve(state: HudState, host: str = "127.0.0.1", port: int = 8787) -> ThreadingHTTPServer:
    p = hud_html_path()
    page = open(p, "rb").read() if p else b"<h1>hud.html not found</h1>"
    httpd = ThreadingHTTPServer((host, port), make_handler(state, page))
    threading.Thread(target=httpd.serve_forever, name="airscope-http", daemon=True).start()
    return httpd
