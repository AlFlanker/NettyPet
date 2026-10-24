#!/usr/bin/env python3
"""Shared local PDF builder using Pandoc, Graphviz and system Chromium."""

from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass
import html
from html.parser import HTMLParser
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import unquote, urlsplit


PAGE_BREAK = re.compile(r"(?m)^[ \t]*<!-- pagebreak -->[ \t]*$")


@dataclass(frozen=True)
class Guide:
    docs_dir: Path
    title: str
    header_label: str
    expected_pages: int
    pdf_filename: str


def run(command: list[str], *, docs_dir: Path, stdin: str | None = None,
        timeout: int = 90) -> str:
    result = subprocess.run(command, input=stdin, text=True, encoding="utf-8",
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            cwd=docs_dir, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(f"Команда завершилась с кодом {result.returncode}: {command[0]}\n"
                           + result.stderr[-5000:])
    return result.stdout


class IdCollector(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=False)
        self.ids: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        for name, value in attrs:
            if name == "id" and value:
                self.ids.append(value)

    handle_startendtag = handle_starttag


class FragmentRewriter(HTMLParser):
    def __init__(self, page: int, ids_by_page: list[set[str]],
                 destinations: dict[str, list[int]], docs_dir: Path) -> None:
        super().__init__(convert_charrefs=False)
        self.page = page
        self.ids_by_page = ids_by_page
        self.destinations = destinations
        self.docs_dir = docs_dir
        self.parts: list[str] = []

    def anchor(self, fragment: str) -> str:
        fragment = unquote(fragment)
        page_link = re.fullmatch(r"page-0*(\d+)", fragment)
        if page_link:
            number = int(page_link.group(1))
            if 1 <= number <= len(self.ids_by_page):
                return f"#sheet-{number}"
            raise ValueError(f"Страница {self.page}: нет страницы {number}")
        if fragment in self.ids_by_page[self.page - 1]:
            return f"#p{self.page:02d}-{fragment}"
        pages = self.destinations.get(fragment, [])
        if len(pages) == 1:
            return f"#p{pages[0]:02d}-{fragment}"
        for number, names in enumerate(self.ids_by_page, 1):
            if fragment.startswith(f"p{number:02d}-") and fragment[len(f"p{number:02d}-"):] in names:
                return "#" + fragment
        if pages:
            raise ValueError(f"Страница {self.page}: неоднозначный anchor #{fragment}; "
                             "задайте уникальный ID или ссылку #page-N")
        raise ValueError(f"Страница {self.page}: не найден anchor #{fragment}")

    def relative_url(self, value: str, *, asset: bool) -> str:
        parsed = urlsplit(value)
        if asset:
            if parsed.scheme or parsed.netloc or parsed.path.startswith("/"):
                raise ValueError(f"Страница {self.page}: нужен относительный локальный asset: {value}")
            path = self.docs_dir / unquote(parsed.path)
            if not path.is_file():
                raise ValueError(f"Страница {self.page}: asset не найден: {path}")
            mime = mimetypes.guess_type(path)[0] or "application/octet-stream"
            return f"data:{mime};base64," + base64.b64encode(path.read_bytes()).decode("ascii")
        if not parsed.scheme and not parsed.netloc:
            if not parsed.path and parsed.fragment:
                return self.anchor(parsed.fragment)
            if parsed.path in ("guide.md", "./guide.md"):
                return self.anchor(parsed.fragment) if parsed.fragment else "#sheet-1"
            if parsed.path and not parsed.path.startswith("/"):
                return "../" + value
        return value

    def start(self, tag: str, attrs: list[tuple[str, str | None]], *, closed: bool) -> None:
        rewritten: list[tuple[str, str | None]] = []
        for name, value in attrs:
            if value is not None:
                if name == "id":
                    page_anchor = re.fullmatch(r"page-0*(\d+)", value)
                    if page_anchor:
                        if int(page_anchor.group(1)) != self.page:
                            raise ValueError(f"Страница {self.page}: anchor {value} относится к другому листу")
                        value = f"page-{self.page}"
                    else:
                        value = f"p{self.page:02d}-{value}"
                elif name == "src" or (tag == "object" and name == "data"):
                    value = self.relative_url(value, asset=True)
                elif name == "href":
                    value = self.relative_url(value, asset=tag not in ("a", "area"))
                elif name == "srcset":
                    raise ValueError("Используйте один локальный src вместо srcset")
            rewritten.append((name, value))
        attributes = "".join(" " + name if value is None else
                             f' {name}="{html.escape(value, quote=True)}"'
                             for name, value in rewritten)
        self.parts.append(f"<{tag}{attributes}{' /' if closed else ''}>")

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.start(tag, attrs, closed=False)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.start(tag, attrs, closed=True)

    def handle_endtag(self, tag: str) -> None:
        self.parts.append(f"</{tag}>")

    def handle_data(self, data: str) -> None:
        self.parts.append(data)

    def handle_entityref(self, name: str) -> None:
        self.parts.append(f"&{name};")

    def handle_charref(self, name: str) -> None:
        self.parts.append(f"&#{name};")

    def handle_comment(self, data: str) -> None:
        self.parts.append(f"<!--{data}-->")


CHECK_SCRIPT = r"""
(async () => {
  const output = document.getElementById('layout-check-result');
  const errors = [];
  const epsilon = 0.5; // CSS-pixel rounding only, not a content overflow allowance.
  const label = element => element.tagName.toLowerCase() +
    (element.id ? '#' + element.id : '') + ': ' + element.textContent.trim().slice(0, 90);
  try {
    await document.fonts.ready;
    await Promise.all(Array.from(document.images, image => new Promise(resolve => {
      if (image.complete) return resolve();
      image.addEventListener('load', resolve, {once: true});
      image.addEventListener('error', resolve, {once: true});
    })));
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const sheets = Array.from(document.querySelectorAll('.sheet'));
    const expectedPages = Number(document.documentElement.dataset.expectedPages);
    if (sheets.length !== expectedPages)
      errors.push({error: 'wrong-sheet-count', expected: expectedPages, actual: sheets.length});
    for (const image of document.images) {
      if (!image.naturalWidth) errors.push({error: 'image-not-loaded', src: image.getAttribute('src')});
    }
    const ids = new Set();
    for (const element of document.querySelectorAll('[id]')) {
      if (ids.has(element.id)) errors.push({error: 'duplicate-id', id: element.id});
      ids.add(element.id);
    }
    for (const link of document.querySelectorAll('a[href^="#"]')) {
      const id = decodeURIComponent(link.getAttribute('href').slice(1));
      if (!document.getElementById(id)) errors.push({error: 'broken-anchor', href: link.getAttribute('href')});
    }
    for (const sheet of sheets) {
      const page = Number(sheet.dataset.page);
      for (const region of sheet.querySelectorAll('.page-header, .page-main, .page-footer')) {
        const box = region.getBoundingClientRect();
        if (region.scrollHeight > region.clientHeight + epsilon)
          errors.push({page, region: region.className, error: 'vertical-overflow',
                       pixels: region.scrollHeight - region.clientHeight});
        if (region.scrollWidth > region.clientWidth + epsilon)
          errors.push({page, region: region.className, error: 'horizontal-overflow',
                       pixels: region.scrollWidth - region.clientWidth});
        for (const element of region.querySelectorAll('*')) {
          const rect = element.getBoundingClientRect();
          if (!rect.width && !rect.height) continue;
          if (rect.top < box.top - epsilon || rect.bottom > box.bottom + epsilon ||
              rect.left < box.left - epsilon || rect.right > box.right + epsilon)
            errors.push({page, region: region.className, error: 'element-outside-region',
                         element: label(element),
                         top: +(rect.top - box.top).toFixed(2),
                         bottom: +(rect.bottom - box.bottom).toFixed(2),
                         left: +(rect.left - box.left).toFixed(2),
                         right: +(rect.right - box.right).toFixed(2)});
        }
      }
    }
    output.textContent = JSON.stringify({ok: errors.length === 0, pages: sheets.length, errors});
  } catch (error) {
    output.textContent = JSON.stringify({ok: false, errors: [{error: String(error)}]});
  }
})();
"""


def build_html(sections: list[str], css: str, pandoc: str, guide: Guide) -> str:
    fragments = [run([pandoc, "--from=markdown", "--to=html5", "--wrap=none"],
                     docs_dir=guide.docs_dir, stdin=section)
                 for section in sections]
    ids_by_page: list[set[str]] = []
    destinations: dict[str, list[int]] = {}
    for number, fragment in enumerate(fragments, 1):
        collector = IdCollector()
        collector.feed(fragment)
        if len(set(collector.ids)) != len(collector.ids):
            raise ValueError(f"Страница {number}: повторяющийся HTML ID")
        ids_by_page.append(set(collector.ids))
        for identifier in collector.ids:
            destinations.setdefault(identifier, []).append(number)
    sheets = []
    for number, fragment in enumerate(fragments, 1):
        rewriter = FragmentRewriter(number, ids_by_page, destinations, guide.docs_dir)
        rewriter.feed(fragment)
        has_page_anchor = any(re.fullmatch(r"page-0*(\d+)", name)
                              and int(name.rsplit("-", 1)[1]) == number
                              for name in ids_by_page[number - 1])
        page_anchor = "" if has_page_anchor else f'<a id="page-{number}"></a>'
        sheets.append(f'''<section class="sheet" id="sheet-{number}" data-page="{number}">
<header class="page-header"><span>{html.escape(guide.title.removesuffix(' — руководство'))}</span><span>{html.escape(guide.header_label)}</span></header>
<main class="page-main">{page_anchor}{''.join(rewriter.parts)}</main>
<footer class="page-footer"><span>Руководство разработчика</span><span>{number} / {guide.expected_pages}</span></footer>
</section>''')
    return f'''<!doctype html>
<html lang="ru" data-expected-pages="{guide.expected_pages}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src file: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src file: data:; connect-src 'none'">
<title>{html.escape(guide.title)}</title><style>{css}</style></head>
<body>{''.join(sheets)}
<pre id="layout-check-result" hidden>{{"ok":false,"status":"pending"}}</pre>
<script>{CHECK_SCRIPT}</script></body></html>'''


def _build(guide: Guide) -> int:
    parser = argparse.ArgumentParser(
        description=f"Локальная сборка {guide.expected_pages}-страничного PDF-гайда")
    parser.add_argument("--check", action="store_true",
                        help="проверить HTML, ссылки и геометрию без записи итогового PDF")
    args = parser.parse_args()
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as failure:
        raise RuntimeError("Нужен Python-пакет playwright >= 1.42: python3 -m pip install playwright") from failure
    required = ("pandoc", "chromium", "dot", "pdfinfo", "fc-match")
    tools = {name: shutil.which(name) for name in required}
    missing = [name for name, path in tools.items() if path is None]
    if missing:
        raise RuntimeError("Не установлены программы: " + ", ".join(missing))
    for family in ("Noto Sans", "Noto Sans Mono"):
        matched = run([tools["fc-match"], "-f", "%{family}", family],
                      docs_dir=guide.docs_dir).strip()
        if family not in matched.split(","):
            raise RuntimeError(f"Не найден шрифт {family}; fontconfig выбрал {matched}")

    markdown = (guide.docs_dir / "guide.md").read_text(encoding="utf-8-sig").strip()
    sections = [section.strip() for section in PAGE_BREAK.split(markdown)]
    if len(sections) != guide.expected_pages:
        raise ValueError(f"Нужно {guide.expected_pages} страниц, найдено {len(sections)}")
    for number, section in enumerate(sections, 1):
        if not re.match(r"^##[ \t]+\S", section):
            raise ValueError(f"Страница {number} должна начинаться с ## Заголовок")
    counts = [len(section.split()) for section in sections]
    print(f"Страниц: {len(sections)}; слов в Markdown: {sum(counts)}")
    print("Слов по страницам: " + ", ".join(f"{i}: {count}" for i, count in enumerate(counts, 1)))

    # Keep HTML, PDF staging and Chromium's complete profile within one removable directory.
    with tempfile.TemporaryDirectory(prefix=".guide-build-", dir=guide.docs_dir) as directory:
        temporary = Path(directory)
        for source in sorted((guide.docs_dir / "diagrams").glob("*.dot")):
            staged = temporary / (source.stem + ".svg")
            run([tools["dot"], "-Tsvg", str(source), "-o", str(staged)],
                docs_dir=guide.docs_dir)
            staged.replace(source.with_suffix(".svg"))
        document = build_html(sections, (guide.docs_dir / "guide.css").read_text(encoding="utf-8"),
                              tools["pandoc"], guide)
        html_path = temporary / "guide.html"
        html_path.write_text(document, encoding="utf-8")
        staged_pdf = temporary / "guide.pdf"
        browser_env = dict(os.environ, XDG_CONFIG_HOME=str(temporary / "config"),
                           XDG_CACHE_HOME=str(temporary / "cache"))
        with sync_playwright() as playwright:
            context = playwright.chromium.launch_persistent_context(
                user_data_dir=str(temporary / "chromium-profile"),
                executable_path=tools["chromium"], headless=True, timeout=20000,
                args=["--no-sandbox", "--disable-dev-shm-usage"],
                env=browser_env)
            try:
                page = context.pages[0] if context.pages else context.new_page()
                page.emulate_media(media="print")
                page.set_content(document, wait_until="load", timeout=30000)
                page.wait_for_function("JSON.parse(document.getElementById('layout-check-result').textContent).status !== 'pending'",
                                       timeout=15000)
                report = json.loads(page.locator("#layout-check-result").text_content())
                if not report.get("ok"):
                    raise RuntimeError("HTML-проверка не пройдена:\n" + json.dumps(report, ensure_ascii=False, indent=2))
                print(f"HTML: ссылки, изображения и геометрия всех {guide.expected_pages} листов проверены; переполнений нет.")
                if args.check:
                    return 0
                page.pdf(path=str(staged_pdf), format="A4", print_background=True,
                         display_header_footer=False, prefer_css_page_size=True,
                         tagged=True, outline=True)
            finally:
                context.close()
        if not staged_pdf.is_file():
            raise RuntimeError("Chromium не создал PDF")
        metadata = run([tools["pdfinfo"], str(staged_pdf)], docs_dir=guide.docs_dir)
        pages = re.search(r"(?m)^Pages:\s+(\d+)\s*$", metadata)
        if pages is None or int(pages.group(1)) != guide.expected_pages:
            raise RuntimeError(f"В PDF должно быть ровно {guide.expected_pages} страниц:\n" + metadata)
        output = guide.docs_dir / guide.pdf_filename
        staged_pdf.replace(output)
        print(f"PDF: {output} ({guide.expected_pages} страниц, {output.stat().st_size} байт)")
    return 0


def main(*, docs_dir: Path, title: str, header_label: str,
         expected_pages: int, pdf_filename: str) -> int:
    """Run the existing ``--check``/PDF CLI with a guide-specific configuration."""
    try:
        if expected_pages < 1:
            raise ValueError("Число страниц должно быть положительным")
        guide = Guide(Path(docs_dir).resolve(), title, header_label, expected_pages, pdf_filename)
        return _build(guide)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as failure:
        print(f"Ошибка: {failure}", file=sys.stderr)
        return 1
