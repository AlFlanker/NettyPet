#!/usr/bin/env python3
"""Потоковый upstream: проверяет полное multipart-тело и каждую часть по локальному эталону."""

import argparse
import hashlib
import json
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass  # Журнал каждого upload искажает нагрузочные измерения.

    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def reply(self, code, data, close=False):
        self.close_connection |= close
        body = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"status": "ok"})
        else:
            self.reply(404, {"verified": False, "error": "unknown path"}, close=True)

    def upload(self):
        expected = self.server.manifest
        if urlsplit(self.path).path != "/multipart":
            self.reply(404, {"verified": False, "error": "use /multipart"}, close=True)
            return
        # Этот нагрузочный fixture использует Content-Length, не HTTP chunked framing.
        if self.headers.get("Transfer-Encoding"):
            self.reply(400, {"verified": False, "error": "Transfer-Encoding is unsupported"}, close=True)
            return
        lengths = self.headers.get_all("Content-Length", [])
        if not lengths:
            self.reply(411, {"verified": False, "error": "Content-Length required"}, close=True)
            return
        if len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdigit():
            self.reply(400, {"verified": False, "error": "invalid Content-Length"}, close=True)
            return
        length = int(lengths[0])
        if length > expected["size"]+65536:
            self.reply(413, {"verified": False, "error": "body too large"}, close=True)
            return
        digest = hashlib.sha256()
        hashes = [hashlib.sha256() for _ in expected["parts"]]
        received = 0
        try:
            while received < length:
                block = self.rfile.read(min(65536, length-received))
                if not block:
                    self.reply(422, {"verified": False, "error": "truncated body",
                                     "actual": received}, close=True)
                    return
                digest.update(block)
                for part, part_hash in zip(expected["parts"], hashes):
                    start = max(received, part["offset"])
                    end = min(received+len(block), part["offset"]+part["size"])
                    if start < end:
                        part_hash.update(memoryview(block)[start-received:end-received])
                received += len(block)
        except socket.timeout:
            self.reply(408, {"verified": False, "error": "body read timeout"}, close=True)
            return
        # Сначала дочитываем ограниченное тело: ответ не конкурирует с upload через прокси.
        if length != expected["size"]:
            self.reply(422, {"verified": False, "error": "body size mismatch",
                             "expected": expected["size"], "actual": length}, close=True)
            return
        if self.headers.get_all("Content-Type", []) != [expected["content_type"]]:
            self.reply(415, {"verified": False, "error": "Content-Type/boundary mismatch"}, close=True)
            return
        parts = [dict(name=p["name"], filename=p["filename"], size=p["size"], sha256=h.hexdigest())
                 for p, h in zip(expected["parts"], hashes)]
        verified = digest.hexdigest() == expected["sha256"] and all(
            actual["sha256"] == wanted["sha256"] for actual, wanted in zip(parts, expected["parts"]))
        self.reply(200 if verified else 422,
                   dict(verified=verified, method=self.command,
                        request_id=self.headers.get("X-Request-Id", ""),
                        size=received, sha256=digest.hexdigest(), parts=parts), close=not verified)

    do_POST = upload
    do_PUT = upload


def load_manifest(path):
    manifest = json.loads(Path(path).read_text(encoding="utf-8"))
    if not manifest["content_type"].startswith("multipart/form-data; boundary="):
        raise ValueError("Not a multipart manifest")
    if manifest["size"] <= 0 or len(manifest["parts"]) != 3:
        raise ValueError("Invalid manifest size or part count")
    end = 0
    for part in manifest["parts"]:
        if not (end <= part["offset"] < part["offset"]+part["size"] < manifest["size"]):
            raise ValueError("Invalid part offsets")
        end = part["offset"]+part["size"]
        if len(bytes.fromhex(part["sha256"])) != 32:
            raise ValueError("Invalid part SHA-256")
    if len(bytes.fromhex(manifest["sha256"])) != 32:
        raise ValueError("Invalid body SHA-256")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9001)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.manifest = load_manifest(args.manifest)
    print(f"Listening on {args.host}:{args.port}; POST/PUT /multipart; "
          f"expected {server.manifest['size']} bytes", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
