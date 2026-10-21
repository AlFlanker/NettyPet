#!/usr/bin/env python3
"""Заглушка бэкенда для эмуляции пути replaceUrlInContent.

Реалистичная ZK-страница: ~8 МиБ тела (по умолчанию), 30–40 ссылок
на css/js/wcs (не тысячи href в каждой строке таблицы).

Что Портал перепишет:

  HtmlContentRewriter
    href="/..."                 — css/wcs и навигация
    href="./..."  href="../..." — относительные (тега <base> нет)
    <form action="...">         — поиск + одна многострочная форма (DOTALL)

  FullLinkContentRewriter
    http://<backend>/...        — должен совпасть с Host.target

  OAMHackContentRewriter
    anti-iframe скрипт OAM      — точная строка из Java

Кириллица в теле: Compact Strings на JDK 11 не сработают, String ≈ UTF-16.

Карточка хоста:

  uri: HEAPREPRO
  target: http://127.0.0.1:14035
  replaceUrlInContent: true
  defaultUri: /index.zul
  timeout: 30000
  prefix:     (пусто)
  uriCaches:  (пусто)

Запуск:

  python3 server.py
  python3 server.py --port 14035 --size-mb 8 --backend-url http://127.0.0.1:14035
"""

from __future__ import annotations

import argparse
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

OAM_ANTI_IFRAME = (
    "if (self==top) {\n"
    "  document.documentElement.style.visibility='visible';\n"
    "} else {\n"
    "  top.location=self.location;\n"
    "}"
)

# Типичный набор ZK/UFOS: css, wcs, js. HtmlContentRewriter смотрит только href=;
# src= абсолютным URL бэкенда подхватывает FullLink.
STYLES = (
    "/static/resources/css/zk.wcs",
    "/static/resources/css/norm.wcs",
    "/static/css/theme.css",
    "/static/css/grid.css",
    "/static/css/print.css",
    "/sufdclient/zkau/web/css/zk.wcs",
    "/versioned/css/app.css",
    "/zkau/web/zul/css/zk.css.dsp",
)

REL_STYLES = (
    "./static/css/main.css",
    "./favicon.ico",
    "../static/css/chunk.css",
    "../images/logo.png",
)

SCRIPTS_ABS = (
    "/zkau/web/js/zk.wpd",
    "/zkau/web/js/zul.wnd.wpd",
    "/zkau/web/js/zul.mesh.wpd",
    "/zkau/web/js/zul.wgt.wpd",
    "/zkau/web/js/zul.sel.wpd",
    "/zkau/web/js/zul.utl.wpd",
    "/zkau/web/js/zul.inp.wpd",
    "/zkau/web/js/zul.box.wpd",
    "/zkau/web/js/zul.layout.wpd",
    "/zkau/web/js/zkmax.wpd",
    "/zkau/web/js/zkex.wpd",
    "/static/js/app.js",
    "/static/js/grid.js",
)

SCRIPTS_REL = (
    "./static/js/boot.js",
    "../zkau/web/js/zul.menu.wpd",
)

NAV_HREFS = (
    "/index.zul",
    "/scroller.zul?navigationId=root",
    "/logout.zul",
)

PAD_TEXT = (
    "Наименование документа, статус согласования, сумма по бюджетной классификации "
    "и комментарий ответственного исполнителя: абвгдеёжзийклмнопрстуфхцчшщъыьэюя. "
)


def backend_origin(backend_url: str) -> str:
    return backend_url.rstrip("/")


def build_head(origin: str) -> str:
    links = []
    for path in STYLES:
        links.append(f'  <link rel="stylesheet" href="{path}"/>')
    for path in REL_STYLES:
        links.append(f'  <link rel="stylesheet" href="{path}"/>')
    # несколько css с полным URL бэкенда — FullLink
    links.append(f'  <link rel="stylesheet" href="{origin}/static/css/brand.css"/>')
    links.append(f'  <link rel="stylesheet" href="{origin}/zkau/web/css/zk.wcs"/>')

    scripts = []
    for path in SCRIPTS_ABS:
        scripts.append(f'  <script src="{path}"></script>')
    for path in SCRIPTS_REL:
        scripts.append(f'  <script src="{path}"></script>')
    scripts.append(f'  <script src="{origin}/zkau/web/js/zk.wpd"></script>')
    scripts.append(f'  <script src="{origin}/static/js/boot.js"></script>')

    nav = ", ".join(f'<a href="{href}">{href.split("?")[0]}</a>' for href in NAV_HREFS)
    nav += f', <a href="{origin}/index.zul">полный URL index.zul</a>'

    return f"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8"/>
  <title>Реестр документов</title>
  <script>
{OAM_ANTI_IFRAME}
  </script>
{chr(10).join(links)}
{chr(10).join(scripts)}
</head>
<body>
  <h1>Реестр документов</h1>
  <p>Навигация: {nav}</p>
  <form name="search" action="/search" method="GET">
    <input type="text" name="q" value="поиск по реестру"/>
    <button type="submit">Найти</button>
  </form>
  <form name="saveDoc" method="POST"
        class="zul-form"
        action="/submit/document">
    <input type="hidden" name="guid" value="demo"/>
    <button type="submit">Сохранить</button>
  </form>
  <table class="zk-grid">
    <thead><tr><th>№</th><th>Документ</th><th>Комментарий</th></tr></thead>
    <tbody>
"""


def build_row(index: int) -> str:
    return (
        f'<tr data-row="{index}">'
        f"<td>{index}</td>"
        f"<td>Документ №{index} от 10.09.2026</td>"
        f"<td>{PAD_TEXT}</td>"
        "</tr>\n"
    )


def build_html(size_mb: float, backend: str) -> bytes:
    origin = backend_origin(backend)
    header = build_head(origin)
    footer = """    </tbody>
  </table>
</body>
</html>
"""
    target = int(size_mb * 1024 * 1024)
    chunks = [header]
    current = len(header.encode("utf-8")) + len(footer.encode("utf-8"))
    index = 0
    row_size = len(build_row(0).encode("utf-8"))
    while current + row_size < target:
        row = build_row(index)
        chunks.append(row)
        current += len(row.encode("utf-8"))
        index += 1
    chunks.append(footer)
    body = "".join(chunks).encode("utf-8")
    print(
        f"HTML {len(body) / (1024 * 1024):.2f} МиБ, строк таблицы {index}, backend={origin}",
        file=sys.stderr,
    )
    return body


def build_css(backend: str) -> bytes:
    origin = backend_origin(backend)
    # Несколько url() — как в реальном zk.wcs, не мегабайты правил.
    css = f"""/* zk.wcs */
.z-grid {{ background: url('{origin}/static/images/bg.png'); }}
.z-icon {{ background: url('/static/images/icon.png'); }}
.z-logo {{ background: url('../images/logo.png'); }}
@import url('{origin}/static/css/theme.css');
@import url('/static/css/grid.css');
"""
    return css.encode("utf-8")


def count_rewrite_targets(html: str, origin: str) -> None:
    import re

    link_tags = len(re.findall(r"<link\b", html, re.I))
    script_src = len(re.findall(r"<script\b[^>]*\bsrc=", html, re.I))
    href_abs = len(re.findall(r'href=["\'](/[^"\']+)["\']', html))
    href_rel = len(re.findall(r'href=["\']((?:\.\./|\./)[^"\']+)["\']', html))
    forms = len(re.findall(r'<form(.+?)action="([^"]+?)"(.*?)>', html, re.DOTALL))
    full = html.count(origin + "/")
    oam = OAM_ANTI_IFRAME in html
    print(
        f"ассеты: <link> {link_tags}, <script src> {script_src} "
        f"(всего css/js ~{link_tags + script_src})",
        file=sys.stderr,
    )
    print(
        f"перезапись: href=/ {href_abs}, href=./|../ {href_rel}, "
        f"form {forms}, полный URL {full}, OAM {oam}",
        file=sys.stderr,
    )


class Handler(BaseHTTPRequestHandler):
    html: bytes = b""
    css: bytes = b""
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def do_HEAD(self) -> None:
        content_type, payload = self._route()
        self._send(content_type, payload, body=False)

    def do_GET(self) -> None:
        content_type, payload = self._route()
        self._send(content_type, payload, body=True)

    def _route(self) -> tuple[str, bytes]:
        path = urlparse(self.path).path.rstrip("/") or "/"
        if path in ("/status", "/health"):
            return "text/plain; charset=utf-8", b"OK"
        if path.endswith(".css") or path.endswith(".wcs"):
            return "text/css; charset=utf-8", self.css
        return "text/html; charset=utf-8", self.html

    def _send(self, content_type: str, payload: bytes, body: bool) -> None:
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body:
            self.wfile.write(payload)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=14035)
    parser.add_argument("--size-mb", type=float, default=1, help="размер HTML, МиБ (по умолчанию 8)")
    parser.add_argument(
        "--backend-url",
        default="http://127.0.0.1:14035",
        help="должен совпасть с Host.target в админке, иначе FullLink не сработает",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.size_mb <= 0:
        sys.exit("--size-mb должен быть > 0")
    print("Собираю HTML/CSS…", file=sys.stderr)
    Handler.html = build_html(args.size_mb, args.backend_url)
    Handler.css = build_css(args.backend_url)
    count_rewrite_targets(Handler.html.decode("utf-8"), backend_origin(args.backend_url))
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    print(
        f"Слушаю http://{args.host}:{args.port}/\n"
        f"  GET /index.zul  /scroller.zul  /  → HTML {len(Handler.html) / (1024 * 1024):.2f} МиБ\n"
        f"  GET /static/resources/css/zk.wcs  → CSS {len(Handler.css)} байт\n"
        f"  GET /status                       → health\n"
        f"Host.target Портала: {backend_origin(args.backend_url)}",
        file=sys.stderr,
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nостановка", file=sys.stderr)


if __name__ == "__main__":
    main()
