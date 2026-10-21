# Multipart POST / PUT с проверкой целостности

Сценарий отправляет одинаковое `multipart/form-data` тело методами POST и PUT.
По умолчанию бинарный файл занимает **8 МиБ**, всё тело немного больше.
Зависимости: Python 3 (только стандартная библиотека), JMeter **5.6.3**, Java 21
и собранное приложение. Команды ниже выполняются из корня репозитория.

## Подготовка данных

```bash
python3 stress/multipart/prepare.py --output /tmp/nettypet-multipart --size-mib 8
```

Генератор создаёт два файла вне репозитория:

- `multipart.body` — готовое тело с фиксированным boundary, UTF-8 полем `comment`,
  JSON-файлом `metadata.json` и бинарным `payload.bin` (все значения байта, включая NUL/CR/LF).
- `manifest.json` — размер и SHA-256 всего тела, имена, размеры, смещения и SHA-256 частей.

Используйте новый каталог для нового размера: генератор отказывается перезаписывать
существующие файлы. Передайте **этот же manifest** upstream и оба файла JMeter.
Не обновляйте эталон во время прогона. Не вычисляйте ожидаемый hash из пришедшего запроса:
эталон хранится независимо на upstream.

## Приёмник и приложение

В первом терминале:

```bash
python3 stress/multipart/server.py --manifest /tmp/nettypet-multipart/manifest.json --port 9001
```

Во втором терминале соберите приложение и запустите его с новым upstream:

```bash
# В подготовленной облачной среде:
/workspace/.setup/gradle :app:bootJar
# В другой среде используйте установленный Gradle 8.14.3.
PROXY_UPSTREAM=http://127.0.0.1:9001 java -jar app/build/libs/app-1.0.0.jar --server.port=8081
```

Приёмник принимает POST/PUT `/multipart`, его health endpoint — GET `/health`.
Он читает тело порциями по 64 КиБ, не сохраняет upload на диск и не удерживает
тело целиком в памяти. Проверяет размер, Content-Type/boundary, SHA-256 **всего
wire-тела**, включая заголовки частей и разделители, и SHA-256 каждой части.
Поскольку тело должно точно совпасть с заранее сформированным fixture, изменение
имени поля/файла, порядка частей, пропуск/добавление части и порча boundary также
проваливают проверку. Это проверка конкретного нагрузочного fixture, а не универсальный
сервер разбора произвольных multipart-форм.

Успех: HTTP 200, `verified: true`, фактические размеры/hash, метод и `X-Request-Id`.
Повреждение тела: HTTP 422; неправильный Content-Type: 415. Нужен Content-Length;
HTTP chunked uploads этим сценарием не покрываются. Чтение ограничено ожидаемым
размером + 64 КиБ, таймаут чтения — 30 секунд.

## Проверка корректности перед нагрузкой

```bash
python3 stress/multipart/smoke.py --data-dir /tmp/nettypet-multipart \
  --base-url http://127.0.0.1:9001/multipart
python3 stress/multipart/smoke.py --data-dir /tmp/nettypet-multipart \
  --base-url http://127.0.0.1:8081/proxy/multipart
```

Каждый запуск независимо разбирает fixture MIME-парсером, затем выполняет 16
HTTP-проверок: повторный POST/PUT на одном соединении, порча каждой из трёх частей,
порча boundary, укороченное тело и неверный Content-Type. Негативные случаи должны
вернуть конкретную ошибку с `verified: false`; один HTTP 200 не считается успехом.
Для ручной проверки обоих методов:

```bash
for method in POST PUT; do
  curl --fail-with-body -X "$method" \
    -H 'Content-Type: multipart/form-data; boundary=NettyPet-Multipart-Integrity-2026' \
    --data-binary @/tmp/nettypet-multipart/multipart.body \
    http://127.0.0.1:8081/proxy/multipart
done
```

## JMeter

Быстрая проверка: 2 потока × 2 итерации × 2 метода = **8 запросов**:

```bash
jmeter -n -t stress/multipart/multipart-post-put.jmx \
  -Jhost=127.0.0.1 -Jport=8081 -Jpath=/proxy/multipart \
  -JdataDir=/tmp/nettypet-multipart -Jthreads=2 -Jramp=0 -Jloops=2 \
  -l /tmp/multipart-smoke.jtl -j /tmp/multipart-smoke.log
python3 stress/multipart/check_results.py /tmp/multipart-smoke.jtl
```

Для нагрузки вместо `-Jloops=2` используйте `-Jloops=-1 -Jduration=600 -Jramp=60`.
По умолчанию: 20 потоков, разгон 5 секунд, длительность 60 секунд, циклы до окончания
времени. За одну итерацию каждый поток последовательно выполняет POST, затем PUT.
Таймера ограничения rps нет. Параметры `host`, `port`, `path`, `dataDir`, `threads`,
`ramp`, `loops`, `duration` действительно используются планом.

В облачной среде полный путь к JMeter:
`/workspace/.setup/tools/apache-jmeter-5.6.3/bin/jmeter`;
перед запуском задайте `JAVA_HOME=/workspace/.setup/tools/jdk-21.0.9+10`.

JMeter отправляет готовый файл **как всё тело запроса**, без дополнительной multipart
обёртки. Это позволяет использовать одинаковое тело для PUT и POST. Не включайте
`Use multipart/form-data` в sampler и не задавайте Content-Length вручную.
JSR223 assertion проверяет HTTP 200, `verified`, метод, уникальный request id,
размер/hash тела и имена/размеры/hash каждой части по локальному manifest.
Проверка выполняется для **каждого** запроса. Скрипты Groovy кэшируются; manifest
читается один раз на поток.

Выход JMeter с кодом 0 сам по себе не подтверждает успешные assertions: обязательно
запускайте `check_results.py`. Для каждого прогона используйте новые JTL/log/report
пути: JMeter может дописывать существующий JTL. Проверяющий скрипт требует непустой
CSV JTL с обоими методами и завершится ненулевым кодом при любой ошибке.

Для прямого базового прогона укажите `-Jport=9001 -Jpath=/multipart`, остальные
параметры и fixture оставьте прежними. Сравнивайте прогоны последовательно, на
одинаково прогретых процессах, без параллельной сборки. Для оценки предельного rps
разнесите генератор, прокси и upstream по машинам и следите за CPU/сетью каждого:
приёмник вычисляет два SHA-256 на байт файла, и эта работа тоже влияет на результат.
