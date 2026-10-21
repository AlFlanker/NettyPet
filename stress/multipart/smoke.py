#!/usr/bin/env python3
"""Позитивные и негативные HTTP-проверки multipart POST/PUT напрямую или через прокси."""

import argparse
import hashlib
import http.client
import io
import json
import uuid
from contextlib import closing
from email import policy
from email.parser import BytesParser
from pathlib import Path
from urllib.parse import urlsplit


def check_fixture(data, manifest):
    assert len(data) == manifest["size"], "Fixture length differs from manifest"
    assert hashlib.sha256(data).hexdigest() == manifest["sha256"], "Fixture SHA-256 differs"
    # Независимый MIME parser подтверждает, что fixture действительно multipart/form-data.
    message = BytesParser(policy=policy.default).parsebytes(
        ("Content-Type: "+manifest["content_type"]+"\r\nMIME-Version: 1.0\r\n\r\n").encode()+data)
    assert message.is_multipart() and not message.defects, "Invalid multipart fixture"
    parts = list(message.iter_parts())
    assert len(parts) == len(manifest["parts"]), "Unexpected MIME part count"
    for received, expected in zip(parts, manifest["parts"]):
        assert not received.defects, "Invalid MIME part"
        payload = received.get_payload(decode=True)
        assert received.get_param("name", header="content-disposition") == expected["name"]
        assert received.get_filename() == expected["filename"]
        assert len(payload) == expected["size"]
        assert hashlib.sha256(payload).hexdigest() == expected["sha256"]


def run(base_url, directory):
    target = urlsplit(base_url)
    if target.scheme != "http" or not target.hostname or target.query or target.fragment:
        raise ValueError("Use an HTTP URL ending with /multipart or /proxy/multipart")
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    data = (directory / "multipart.body").read_bytes()
    check_fixture(data, manifest)
    checks = 0

    def send(method, body, expected_status, content_type=None, connection=None):
        nonlocal checks
        request_id = str(uuid.uuid4())
        headers = {"Content-Type": content_type or manifest["content_type"],
                   "Content-Length": str(len(body)), "X-Request-Id": request_id}
        owned = connection is None
        conn = connection or http.client.HTTPConnection(target.hostname, target.port or 80, timeout=60)
        try:
            # file-like body заставляет клиента отправлять данные небольшими порциями.
            try:
                conn.request(method, target.path or "/", io.BytesIO(body), headers=headers)
            except BrokenPipeError:
                # Upstream может отвергнуть заголовки до окончания upload.
                # Всё равно требуем конкретный HTTP-ответ и verified=false ниже.
                if expected_status == 200:
                    raise
            response = conn.getresponse()
            result = json.loads(response.read())
            assert response.status == expected_status, (method, response.status, result)
            if expected_status == 200:
                assert result["verified"] is True
                assert result["request_id"] == request_id and result["method"] == method
                assert result["size"] == len(data) and result["sha256"] == manifest["sha256"]
                expected_parts = [{k: p[k] for k in ("name", "filename", "size", "sha256")}
                                  for p in manifest["parts"]]
                assert result["parts"] == expected_parts
            else:
                assert result["verified"] is False, "Corrupt body reported as verified"
            checks += 1
        finally:
            if owned:
                conn.close()

    for method in ("POST", "PUT"):
        # Повторное использование соединения ловит непрочитанное тело предыдущего запроса.
        with closing(http.client.HTTPConnection(target.hostname, target.port or 80, timeout=60)) as connection:
            send(method, data, 200, connection=connection)
            send(method, data, 200, connection=connection)
        for part in manifest["parts"]:
            damaged = bytearray(data)
            damaged[part["offset"]+part["size"]//2] ^= 1
            send(method, damaged, 422)
        damaged = bytearray(data)
        damaged[-5] ^= 1  # Повреждённое multipart framing при неповреждённых файлах.
        send(method, damaged, 422)
        send(method, data[:-7], 422)
        send(method, data, 415, content_type="multipart/form-data; boundary=wrong")
    print(f"PASS: {checks} HTTP checks at {base_url}; fixture and every part validated")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/proxy/multipart")
    parser.add_argument("--data-dir", type=Path, required=True)
    args = parser.parse_args()
    run(args.base_url, args.data_dir)
