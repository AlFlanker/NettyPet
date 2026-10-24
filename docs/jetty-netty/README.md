# PDF-гайд Jetty + Netty

[Готовый PDF](jetty-netty-guide.pdf) — 17 страниц и 8 векторных диаграмм по основной
связке `app` + `proxy` + `command-support`. Текст находится в [guide.md](guide.md),
оформление — в [guide.css](guide.css), схемы — в `diagrams/*.dot` и `*.svg`.

Команды выполняются из корня репозитория:

```bash
python3 docs/jetty-netty/build_guide.py --check
python3 docs/jetty-netty/build_guide.py
```

Нужны Python 3.10+, Python-пакет `playwright >= 1.42`, локальные Pandoc, Chromium,
Graphviz (`dot`), `pdfinfo`, `fc-match`, шрифты Noto Sans и Noto Sans Mono.
Для использованной версии Playwright:

```bash
python3 -m pip install 'playwright==1.62.0'
```

В Debian остальные зависимости предоставляют `pandoc`, `chromium`, `graphviz`,
`poppler-utils`, `fontconfig`, `fonts-noto-core`, `fonts-noto-mono`.
Playwright использует системный Chromium из PATH; `playwright install` не нужен.
Проверенный набор: Pandoc 3.1.11.1, Chromium 154.0.8037.57, Graphviz 2.42.4,
Playwright 1.62.0. Сборка не скачивает ассеты и не меняет зависимости приложения.

Wrapper вызывает общий [сборщик](../pdf_guide.py), который обслуживает также гайд
Servlet + Jetty HttpClient. Pandoc преобразует Markdown в HTML, Graphviz обновляет
SVG, Playwright передаёт HTML с локально встроенными изображениями Chromium.
PDF сохраняется как `docs/jetty-netty/jetty-netty-guide.pdf`.

В Markdown ровно 17 разделов, разделённых строкой `<!-- pagebreak -->`; каждый
начинается с `## Заголовок` и соответствует одному листу A4. Идентификаторы
`<a id="page-N"></a>` обеспечивают навигацию в Markdown. В PDF ссылки `#page-N`
ведут на начало соответствующего листа. Дубли ID, несуществующие ссылки,
незагруженные изображения и переполнение областей текста/колонтитулов дают ошибку.
Текст не уменьшается автоматически и не обрезается; геометрический допуск 0.5 CSS px
учитывает округление.

Обычная сборка выполняет те же проверки, что `--check`, затем создаёт tagged PDF
с оглавлением и проверяет число страниц. Старый PDF заменяется только при успехе.
`--check` не заменяет PDF, но обновляет SVG из DOT. Временные HTML и профиль браузера
удаляются. Дата создания PDF меняется между сборками; побайтовая идентичность
не обещается. Для сопоставимой вёрстки используйте одинаковые версии инструментов.

Основной текст — 10.5 pt, код и таблицы — 9 pt. При добавлении раздела обновите
`expected_pages` в wrapper, оглавление и ссылки, затем проверьте геометрию и
отрисованные страницы. Java-тесты и нагрузка не запускаются сборщиком документации.
