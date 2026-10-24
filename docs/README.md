# Руководства по двум вариантам прокси

| Вариант | PDF | Редактирование и пересборка |
| --- | --- | --- |
| Основной: Jetty + Netty, команды и Resilience4j | [17 страниц, 8 схем](jetty-netty/jetty-netty-guide.pdf) | [Исходники и инструкция](jetty-netty/README.md) |
| Самостоятельный Servlet + Jetty HttpClient | [15 страниц, 7 схем](../jetty-client-example/docs/jetty-client-guide.pdf) | [Исходники и инструкция](../jetty-client-example/docs/README.md) |

Оба wrapper используют общий `pdf_guide.py`: Pandoc, Graphviz, Playwright и системный
Chromium. Проверки изображений, ссылок, геометрии и количества страниц общие;
оформление и исходники каждого гайда хранятся рядом с его PDF.
