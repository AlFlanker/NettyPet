#!/usr/bin/env python3
"""Создаёт фиксированное multipart-тело и эталон для независимой проверки upstream."""

import argparse
import hashlib
import json
from pathlib import Path

BOUNDARY = "NettyPet-Multipart-Integrity-2026"
CONTENT_TYPE = f"multipart/form-data; boundary={BOUNDARY}"
COMMENT = "Документ №42: проверка POST/PUT, UTF-8 и бинарного файла.\r\nВторая строка."


def prepare(directory: Path, size_bytes: int) -> dict:
    if size_bytes <= 0:
        raise ValueError("size_bytes must be positive")
    directory.mkdir(parents=True, exist_ok=True)
    body_path = directory / "multipart.body"
    manifest_path = directory / "manifest.json"
    # Не перезаписываем эталон, пока запущенные клиенты могут его использовать.
    if body_path.exists() or manifest_path.exists():
        raise FileExistsError("Use a new directory; multipart.body or manifest.json already exists")
    whole = hashlib.sha256()
    offset = 0
    parts = []
    with body_path.open("xb") as out:
        def write(data):
            nonlocal offset
            out.write(data)
            whole.update(data)
            offset += len(data)

        def part(name, filename, content_type, chunks):
            disposition = f'form-data; name="{name}"'
            if filename is not None:
                disposition += f'; filename="{filename}"'
            write((f"--{BOUNDARY}\r\nContent-Disposition: {disposition}\r\n"
                   f"Content-Type: {content_type}\r\n\r\n").encode("ascii"))
            start = offset
            digest = hashlib.sha256()
            for chunk in chunks:
                write(chunk)
                digest.update(chunk)
            parts.append(dict(name=name, filename=filename, offset=start,
                              size=offset-start, sha256=digest.hexdigest()))
            write(b"\r\n")

        part("comment", None, "text/plain; charset=utf-8", [COMMENT.encode("utf-8")])
        part("metadata", "metadata.json", "application/json; charset=utf-8",
             [json.dumps({"document": 42, "title": "Тестовый документ", "valid": True},
                         ensure_ascii=False, separators=(",", ":")).encode("utf-8")])
        def binary():
            block = bytes(range(256)) * 256  # NUL, CR/LF, невалидный UTF-8, все значения байта.
            remaining = size_bytes
            while remaining:
                chunk = block[:min(remaining, len(block))]
                yield chunk
                remaining -= len(chunk)
        part("file", "payload.bin", "application/octet-stream", binary())
        write(f"--{BOUNDARY}--\r\n".encode("ascii"))
    manifest = dict(content_type=CONTENT_TYPE, size=offset, sha256=whole.hexdigest(), parts=parts)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--size-mib", type=int, default=8, help="размер бинарной части, по умолчанию 8 МиБ")
    args = parser.parse_args()
    result = prepare(args.output, args.size_mib * 1024 * 1024)
    print(json.dumps(result, ensure_ascii=False, indent=2))
