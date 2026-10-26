# Host-приложение: выбор обработчика в HttpProxyFilter

Это отдельное собираемое приложение, которое зависит от обычных JAR
`jetty-proxy` и `jetty-proxy-spring`. Оно не зависит от executable
`jetty-client-example` и не сканирует его пакеты.

```text
HttpProxyFilter
  ├─ local → MVC /local
  ├─ maintenance / backend missing / forbidden → локальный отказ
  └─ один выбранный ProxyRequestHandler
       ├─ JettyProxyHandler → JettyProxyEngine
       └─ LegacyProxyHandler → существующий Netty ProxyEngine

MVC /resource → ProxyResourceLoader → bounded Jetty fetch
```

Проверки маршрута и подготовка `BackendRoute` выполняются до чтения тела и async.
После передачи запроса обработчику фильтр не продолжает chain и не отправляет
тот же запрос другой реализацией. По умолчанию приложение использует новый
обработчик. Класс `LegacyHandlerConfiguration` можно явно импортировать при
сборке host-конфигурации для использования старого транспорта; runtime feature
toggle здесь не реализован.

Legacy-ветка использует настоящий Netty `ProxyEngine` из модуля `proxy` с
переносимым Servlet writer. Это проверка совместимости подключения, а не
стенд сравнения производительности с native writer основного `app`.
Портальные классы из `examples/proxy` не входят в source sets этого приложения.

## Запуск

Из корня репозитория, JDK 21 и подготовленный upstream:

```bash
/workspace/.setup/gradle :jetty-proxy-integration-example:build
PROXY_UPSTREAM=http://127.0.0.1:9000/base \
  java -jar jetty-proxy-integration-example/build/libs/jetty-proxy-integration-example-1.0.0.jar
```

По умолчанию host слушает `127.0.0.1:8082`. Настройки маршрутизации находятся
в `host.proxy`, настройки транспорта — в `jetty.proxy`. Библиотеки не содержат
своего `application.yml`, маршрутов или настроек server port.

```bash
curl -i http://127.0.0.1:8082/local
curl -i 'http://127.0.0.1:8082/backend/a%20b?x=1&x=2'
curl -i -X PUT -H 'Expect:' -F 'file=@payload.bin' http://127.0.0.1:8082/backend/upload
curl -i 'http://127.0.0.1:8082/resource?path=%2Fgreeting'
```

`/backend` удаляет только собственный префикс и сохраняет encoded path/query.
`/backend/forbidden` и дочерние пути дают `403` до вызова обработчика. Guard
проверяет однократно декодированный путь, включая `%66orbidden`; raw target
сохраняется отдельно. Правила реального приложения должны соответствовать
нормализации путей его backend — демонстрационная policy не заменяет их.
`host.proxy.maintenance=true` даёт `503` для proxy-маршрутов, сохраняя локальный MVC.
Порядок Filter позволяет оставить обычный MVC FormContentFilter включённым:
проксируемый PUT проходит до разбора формы.

Синхронный loader всегда выполняет отдельный GET по переданному пути; он не
переотправляет исходный POST/PUT. Допустим encoded origin-relative path с query;
полный клиентский URL запрещён. Пример ограничивает wire body 8 МиБ, decoded
body 16 МиБ и строку 8 млн символов. Gzip/deflate распаковываются с пределом,
charset берётся из `Content-Type`. MVC endpoint формирует собственный UTF-8
ответ и сохраняет отдельные `Set-Cookie`, не копируя старую compression/framing
metadata в пере-кодированный ответ.

## Проверки подключения

`verifyLibraryArtifacts` проверяет реальные runtime JAR:

- классы библиотек доступны от корня JAR;
- нет `BOOT-INF` и `application.yml`;
- нет зависимости от executable demo.

HTTP-тесты запускают host отдельно с каждым обработчиком и проверяют
raw URI/context path, multipart POST/PUT, form PUT, SHA-256 тела GET 8 МиБ,
реальные wrappers, cookies, локальные маршруты и отдельный resource loader.
Unit-тесты подтверждают отсутствие чтения тела и запуска async до handoff.

## Независимый потребитель опубликованных координат

`published-consumer` имеет собственный `settings.gradle`. Он подключает
`dev.example:jetty-proxy:1.0.0` и `dev.example:jetty-proxy-spring:1.0.0` по Maven
координатам, без `project()` и composite build. Проверяются обычный JAR/classpath,
factory + bounded fetch, пассивное присутствие Spring-интеграции и явный `@Import`,
совместное существование другого `HttpClient`, lifecycle и отсутствие demo/routes.

```bash
bash jetty-proxy-integration-example/verify-published-consumer.sh /workspace/.setup/gradle
```

Скрипт публикует библиотеки только в локальные игнорируемые `build/repository`,
затем собирает и запускает независимого потребителя дважды: через Gradle module
metadata и только через Maven POM (с отключённой переадресацией на `.module`).
Никакой внешний registry не изменяется. Нужны JDK 21 и Gradle 8.14.3; путь к
локальному Gradle передаётся первым аргументом, остальные аргументы, например
`--offline`, передаются обоим build. Самостоятельный запуск consumer также
допускает `-PpublicationRoot=/путь/к/NettyPet`.
