# Servlet + Jetty HttpClient

Самостоятельный пример на Java 21, Spring Boot **4.1.1** и Jetty **12.1.12** из BOM
Spring Boot. Встроенный Jetty принимает запросы, отдельный Jetty `HttpClient`
передаёт их фиксированному HTTP(S) upstream по HTTP/1.1. Модуль не зависит от
`app`, `proxy` и `command-support` и не содержит Spring Security.

Это отдельный пример, а не замена транспорта основного приложения: здесь нет
RequestProcessor, команд и Resilience4j. Результаты ревью, выполненные проверки
и контрольные замеры с их ограничениями описаны в [REVIEW.md](REVIEW.md).

## Запуск

Нужны JDK 21 и отдельно запущенный upstream. Все команды ниже выполняются из корня
репозитория. Используйте Gradle **8.14.3**: скрипт `gradlew` есть, но файлы
`gradle/wrapper` в текущем checkout отсутствуют. В подготовленной облачной среде
задайте `NETTYPET_GRADLE=/workspace/.setup/gradle`; локально с установленным Gradle —
`NETTYPET_GRADLE=gradle`.

```bash
NETTYPET_GRADLE=/workspace/.setup/gradle # В этой облачной среде; локально: gradle.
"$NETTYPET_GRADLE" :jetty-client-example:test :jetty-client-example:bootJar
PROXY_UPSTREAM=http://127.0.0.1:9000 java -jar jetty-client-example/build/libs/jetty-client-example-1.0.0.jar
```

Или запуск из исходников:

```bash
PROXY_UPSTREAM=http://127.0.0.1:9000 "$NETTYPET_GRADLE" :jetty-client-example:bootRun
```

По умолчанию приложение слушает **127.0.0.1:8081**, upstream — **127.0.0.1:9000**.
Параметры можно менять аргументами Spring Boot, например:

```bash
"$NETTYPET_GRADLE" :jetty-client-example:bootRun --args='--server.port=8082 --example.proxy.max-concurrent=32'
```

Проверка локального MVC-маршрута и проксирования:

```bash
curl -i 'http://127.0.0.1:8081/local'
curl -i 'http://127.0.0.1:8081/proxy'
curl --path-as-is -i 'http://127.0.0.1:8081/proxy/a%20b?x=%2F&x=2'
```

`/local` возвращает `local`. Остальные два запроса требуют соответствующего
маршрута на upstream; ответ `404` upstream также передаётся клиенту.

## Маршрутизация и тела

`JettyProxyServlet` явно зарегистрирован на **`/proxy` и `/proxy/*`** с поддержкой
Servlet async. Остальные маршруты обслуживает обычный Spring MVC. При upstream
`http://127.0.0.1:9000/base`:

| Входящий запрос | Upstream |
| --- | --- |
| `/proxy` или `/proxy/` | `/base/` |
| `/proxy/a?x=1&x=2` | `/base/a?x=1&x=2` |
| `/proxying` | Не проксируется |

Путь и query принятого контейнером запроса используются без дополнительного
декодирования и нормализации. Уже записанные `%XX` не кодируются повторно.
Контейнер может отклонить неоднозначный URI до вызова Servlet.

Тела передаются потоком, без сборки полного запроса или ответа в памяти. JSON,
бинарные данные, multipart и сжатые представления остаются непрозрачными:
прокси не разбирает части multipart и не распаковывает `Content-Encoding`.
HTTP framing, в том числе границы chunked-порций, на каждом соединении формируется
заново; сохранение исходных границ не обещается. Глобальный MVC `FormContentFilter`
отключён в этом примере, чтобы не читать form PUT/PATCH/DELETE до прокси.

Для upload-проверок нужен upstream, принимающий соответствующие методы и пути.
Замените `payload.bin` на существующий файл:

```bash
curl --http1.1 -H 'Expect:' --data-binary @payload.bin 'http://127.0.0.1:8081/proxy/upload'
curl --http1.1 -X PUT -H 'Expect:' -H 'Transfer-Encoding: chunked' --data-binary @payload.bin 'http://127.0.0.1:8081/proxy/upload'
curl --http1.1 -H 'Expect:' -F 'file=@payload.bin' -F 'description=sample' 'http://127.0.0.1:8081/proxy/upload'
```

Для проверки form PUT без предварительного разбора тела:

```bash
curl --http1.1 -X PUT -H 'Expect:' -H 'Content-Type: application/x-www-form-urlencoded' --data-binary 'x=%2F&x=2' 'http://127.0.0.1:8081/proxy/form'
```

## Настройки клиента

Настройки находятся в `src/main/resources/application.yml`, префикс —
`example.proxy`. Адрес `upstream` допускает base path; user info, query и fragment
в нём запрещены. `PROXY_UPSTREAM` переопределяет адрес по умолчанию.

| Параметр | По умолчанию | Значение |
| --- | --- | --- |
| `chunk-bytes` | `65536` | Порция upload, буфер чтения upstream и массив переносимой записи; допустимо 1024–65536 байт |
| `max-concurrent` | `128` | Общий предел принятых незавершённых обменов; при заполнении `503` |
| `max-connections-per-destination` | `64` | Предел соединений клиента на destination |
| `max-queued-requests-per-destination` | `64` | Предел очереди ожидания соединения на destination |
| `selectors` | `2` | Сетевые selectors отдельного клиента |
| `connect-timeout` | `3s` | Таймаут установки соединения клиента |
| `exchange-timeout` | `60s` | Общий срок обмена, включая очередь и передачу тела |
| `idle-timeout` | `30s` | Таймаут отсутствия сетевого трафика, в том числе при активном обмене |

Лимиты destination общие: они не умножаются на `selectors`. Это семантика Jetty
HttpClient, которая отличается от пулов по event loop в основном Netty-прокси.
Размер порции не является пределом всей памяти: клиент, контейнер, TLS и сокеты
используют собственные буферы.

Один клиент создаётся на приложение и останавливается вместе с ним. Конфигурация
явно меняет стандартное поведение `HttpClient`: redirect выключен, cookie store
пустой, прозрачные декодеры и обработчики автоматической аутентификации/upgrade
отключены. Клиент не добавляет свои `User-Agent` и `Content-Type` при отсутствии
этих заголовков во входящем запросе. Обработчики промежуточных `100/102/103` и fallback для остальных `1xx`, кроме
`101`, сохраняют корректное ожидание финального ответа. Cookies из заголовков текущего запроса и ответа
передаются; клиент не хранит cookies между пользователями. HTTPS использует
обычную проверку доверия сертификату и имени узла; trust-all не включён.

Мелкие уже доступные фрагменты ответа объединяются в порцию не больше
`chunk-bytes`, максимум из 1024 фрагментов. Прокси не ждёт новые данные ради
заполнения порции: одиночные события и заголовки отправляются сразу. Крупные
фрагменты не копируются ради объединения; при объединении допускается одна
порция и один отложенный крупный chunk. Это ограничение полезных данных, а не
всей памяти JVM, parser или сокетов.

## Добавление заголовков

Объявите Spring bean типа `ProxyHeaderCustomizer` в конфигурации приложения.
Без таких beans заголовки дополнительно не меняются. `@Order` задаёт порядок
вызовов от меньшего значения к большему, одинаковый для обоих направлений.
Например, в пакете `dev.example.jettyclient`:

```java
package dev.example.jettyclient;

import dev.example.jettyclient.proxy.ProxyHeaderCustomizer;
import dev.example.jettyclient.proxy.ProxyHeaderEditor;
import dev.example.jettyclient.proxy.ProxyRequestMetadata;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration(proxyBeanMethods = false)
class ExampleHeaders {
    @Bean
    @Order(10)
    ProxyHeaderCustomizer serviceHeader() {
        return new ProxyHeaderCustomizer() {
            @Override
            public void customizeRequest(ProxyRequestMetadata request, ProxyHeaderEditor headers) {
                headers.set("X-Example-Service", "gateway");
            }

            @Override
            public void customizeResponse(ProxyRequestMetadata request, int status, ProxyHeaderEditor headers) {
                headers.add("X-Example-Service", "gateway");
            }
        };
    }

    @Bean
    @Order(20)
    ProxyHeaderCustomizer laterHeader() {
        return new ProxyHeaderCustomizer() {
            @Override
            public void customizeRequest(ProxyRequestMetadata request, ProxyHeaderEditor headers) {
                if (headers.values("X-Example-Service").contains("gateway")) {
                    headers.add("X-Example-Stage", "after-service");
                }
            }

            @Override
            public void customizeResponse(ProxyRequestMetadata request, int status, ProxyHeaderEditor headers) {
                headers.set("X-Example-Upstream-Status", Integer.toString(status));
            }
        };
    }
}
```

`set` заменяет все значения заголовка, `add` сохраняет предыдущие, `remove`
удаляет их без учёта регистра имени. `values` возвращает неизменяемый снимок
текущих значений. Повторные `Set-Cookie` не объединяются в одну строку.

`ProxyRequestMetadata` содержит method, готовый upstream URI и глубокий
неизменяемый снимок исходных заголовков. Ключи `headers()` — в нижнем регистре;
`headerValues(name)` ищет без учёта регистра. В response hook передаётся тот же
снимок запроса, а не изменённые первым hook заголовки. Servlet, тело и объект
сетевого запроса в API не предоставляются.

Каждый hook вызывается один раз: request — до отправки upstream, response —
для финального ответа до установки и отправки заголовков клиенту. Beans общие
для параллельных обменов и должны быть потокобезопасными. Hooks обязаны быстро
завершаться без блокировок, чтения тела, обращения к сети или БД: на том же
владельце обмена обрабатываются отмена и deadline. Редактор нельзя сохранять или
передавать другому потоку; после завершения обработки его изменение запрещено.

Редактор запрещает менять `Host`, `Content-Length`, `Expect`, hop-by-hop
заголовки и описание непрозрачного тела: `Content-Type`, `Content-Encoding`,
`Content-Range`. Прокси повторно фильтрует итоговые заголовки, включая имена,
перечисленные исходным `Connection`; такие поля не возвращаются даже через
customizer. Неверное имя, CR/LF или другие недопустимые символы в значении, попытка изменить
защищённый заголовок или исключение hook дают локальный `500` до commit.
При ошибке request hook запрос upstream не отправляется. Статус, URI, тело и
жизненный цикл обмена это расширение не меняет.

## Границы примера

`CONNECT` и `Upgrade` не поддерживаются (`501`); любой входящий `Expect`
отклоняется (`417`), поэтому upload-команды выше явно убирают этот заголовок.
Туннели, WebSocket и HTTP/2 в пример не входят. Не заявляется передача trailers
или промежуточных ответов downstream. Redirect upstream возвращается клиенту,
а не выполняется самим прокси.

Неблокирующий I/O и использование `ByteBuffer` сами по себе не означают полного
отсутствия копий. Это пример streaming-интеграции, без обещания zero-copy.
Счётчик `activeRequests()` отражает незавершённые обмены примера; он не является
контрактом ожидания физического закрытия TCP/TLS-канала из основного `ProxyCall`.

Аутентификации и авторизации сейчас нет. При будущем добавлении Spring Security
проверки доступа должны выполняться всей применимой цепочкой **до** Servlet.
Потребуется отдельно проверить wrappers, async lifecycle и фильтры, читающие тело;
в этом модуле такие механизмы не настраиваются.
