# Кэш при подключении Jetty proxy

Эти классы принадлежат приложению. Они исправляют кэш из `examples/proxy` и не
входят в публикуемые `jetty-proxy` / `jetty-proxy-spring` JAR. Для переноса нужны
совместимый Servlet API, Guava и собственная policy приложения: какие маршруты
допускают общий кэш, как выбирается backend и какие заголовки возвращаются клиенту.

## Накопление ответа при промахе

`ContentCachingResponse` передаёт `isReady()` и `setWriteListener()` реальному
Servlet output. При `isReady()==false` новый Jetty engine ждёт уведомления
контейнера. Обёртка не скрывает response от engine и сохраняет portable путь
записи через Servlet API.

Тело передаётся клиенту сразу и параллельно копируется в ограниченные сегменты.
Известный слишком большой `Content-Length`, превышение лимита, нехватка общего
бюджета или ошибка отменяют накопление. Передача ответа продолжается без кэша;
ошибка самой записи остаётся ошибкой обмена. Listener инвалидирует накопленное
тело до передачи `onError()` обработчику proxy.

| Ограничение | По умолчанию | Что учитывается |
| --- | ---: | --- |
| Тело одной записи | 8 МиБ | Полное тело, отправляемое через обёртку |
| Общий бюджет накопления | 128 МиБ | `2 × maxCaptureBytes` на выбранный ответ: сегменты и финальный массив |
| Заголовки одной записи | 256 значений / 32 Ки символов | Локальный снимок metadata; превышение отключает накопление |
| Вес готового кэша | 128 МиБ | Тела, ключи и оценка памяти metadata |

Резерв берётся перед первым накоплением, а не выделяется целиком при создании
обёртки. При лимите 8 МиБ один miss резервирует 16 МиБ; стандартный бюджет допускает
восемь таких накоплений. Очередь публикации удерживает этот резерв до исполнения
или отмены задачи. `CaptureBudget.reservedBytes()` показывает резерв payload,
а не RSS процесса. Долгие cache hits могут удерживать уже вытесненные записи;
число активных запросов и их deadlines ограничивает приложение отдельно.

`finishCapture(true)` допустим только после подтверждённого успешного завершения.
Метод возвращает `Optional<CachedResponse>`; запись создаётся только для полного
HTTP 200 и согласованного `Content-Length`, если он был объявлен. Частичные,
отменённые и превышающие лимиты ответы возвращают `Optional.empty()`. Повторное
завершение безопасно. `toCachedResponse()` больше не выдаёт незавершённое тело.

## Подключение к новой ветке

Создавайте общий бюджет, store и ограниченный executor один раз на приложение.
Окончательное копирование до 8 МиБ выполняется на executor публикации. Использовать
неограниченный executor или молча отбрасывающую задачи rejection policy нельзя:
неисполненная задача удержит резерв накопления.

Ниже фрагменты обработчика miss. `plan` и backend уже проверены приложением;
`applicationAllowsSharedCache` обозначает его решение о публичности маршрута.

```java
final var budget = new ContentCachingResponse.CaptureBudget(128L * 1024 * 1024);
final var cache = new SharedResponseCache();
final var publications = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.AbortPolicy());
```

В методе обработчика, возвращающем `ProxyCall`:

```java
// На исходном Servlet dispatch, до передачи запроса engine:
if (!applicationAllowsSharedCache || !SharedResponseCache.eligibleRequest(request)) {
    return engine.forward(request, response, plan);
}
String key = SharedResponseCache.key(backendName,
        request.getRequestURI(), request.getQueryString());
var capture = new ContentCachingResponse(response, 8 * 1024 * 1024, budget);
ProxyCall call = engine.forward(request, capture, plan);
call.completion().whenComplete((result, failure) -> {
    if (failure != null || result.failure() != null || result.status() != 200) {
        capture.discardCapture();
        return;
    }
    try {
        publications.execute(new Publication(key, capture, cache));
    } catch (RejectedExecutionException fullOrStopped) {
        capture.discardCapture();
    }
});
return call;
```

Задача публикации содержит только сохранённые данные:

```java
record Publication(String key, ContentCachingResponse capture,
                   SharedResponseCache cache) implements Runnable {
    @Override public void run() {
        try {
            capture.finishCapture(true).ifPresent(value -> cache.put(key, value));
        } finally {
            capture.discardCapture();
        }
    }
    void discard() { capture.discardCapture(); }
}
```

Callback завершения и `Publication` читают только подготовленный ключ и локальное
состояние capture. Они не читают Servlet request/response, не записывают fallback
и не завершают `AsyncContext`: этим уже владеет engine. При отмене приложение
вызывает `call.cancel(cause)`; `discardCapture()` можно вызвать сразу, не ожидая
transport cleanup.

При shutdown сначала прекратите новые запросы и закройте engine. Если задачи
публикации отменяются, освободите каждую снятую с очереди capture:

```java
for (Runnable pending : publications.shutdownNow()) {
    if (pending instanceof Publication publication) publication.discard();
}
```

Уже начавшаяся задача выполняет свой `finally`; при необходимости приложение
ожидает её завершения ограниченное время. Готовую запись и массив тела после
публикации изменять нельзя.

## Готовые записи и попадание в кэш

`SharedResponseCache` ограничивает вес записей и удаляет устаревшие результаты.
Ключ включает backend, исходный URI и raw query. `eligibleRequest()` пропускает
только GET без `Authorization`, `Cookie`, `Range`, `If-*`, `Pragma` и запрещающих
cache directives. Это дополнительная проверка: публичность маршрута и влияние
прикладной идентичности на ответ определяет host.

Store сохраняет только HTTP 200 с явным оставшимся сроком свежести. Ответы
`private`, `no-cache`, `no-store`, `Set-Cookie`, `Vary` и `Content-Range` обходят
общий кэш. Такая policy намеренно уменьшает набор кэшируемых запросов по сравнению
со старой неограниченной map. Неподходящие ответы продолжают передаваться клиенту.
Проверка заголовков ответа в store выполняется при публикации: к этому моменту
тело могло быть накоплено в пределах бюджета и собрано в финальный массив.
Для персонализированного кэша нужен отдельный контракт ключей и изоляции.

`CachedResponseWriter.write(async, response, bytes, completion)` выдаёт готовое
тело порциями до 64 КиБ через настоящий `WriteListener`. До вызова приложение
устанавливает status, разрешённые заголовки, точный `Content-Length` и конечный
async timeout. После передачи владения приложение не дописывает response.
Completion вызывается один раз при terminal событии; сам по себе он не означает,
что клиент получил всё тело. Массив не копируется целиком на каждый cache hit и
должен оставаться неизменяемым до завершения записи.

Кэш хранит полные тела в heap. Он добавляет копирование при miss и финальную
сборку массива; лимиты ограничивают эту работу. Измерения скорости обычного
streaming нельзя переносить на этот путь без отдельного benchmark.

## Проверка и границы переноса

Host-модуль компилирует настоящие `examples/proxy/caching/*.java` в отдельный
`legacyCacheFixture`, доступный только тестам. Остальные портальные классы не
собираются: их закрытые зависимости отсутствуют. Поэтому изменение
`NettyProxyRequestExecutor` показывает место интеграции, а тесты проверяют
непосредственно cache helpers и их взаимодействие с новым engine.

В тестах предусмотрены 8 МиБ с SHA-256, медленные cache miss/hit с настоящим
backpressure, отмена, нехватка общего бюджета, declared/chunked overflow,
metadata и lifecycle. Запуск из корня:

```bash
gradle :jetty-proxy-integration-example:test
```

Проверка кода `5206220`: полная `gradle build` успешна, **649 тестов без ошибок
и пропусков**. Host suite содержит 72 теста, включая 36 новых cache-сценариев:
15 wrapper, 10 writer, 6 store и 5 проверок с настоящим Jetty. Неизменённые задачи
Gradle переиспользованы. Список сценариев, лимиты и SHA-256 исходников:
[verification.json](verification.json).

Старая `ServletBodyWriterFactory` из `examples/proxy/streaming` по-прежнему
выбирает blocking writer для обёртки, перехватывающей output. Исправленная
обёртка обеспечивает неблокирующую работу с новым Jetty engine; перенос writer
старого Netty pipeline этим изменением не выполнен.
