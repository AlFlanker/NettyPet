# Spring adapter для Jetty Proxy

Необязательный обычный JAR поверх [jetty-proxy](../jetty-proxy/README.md).
Поддерживает Spring Boot **4.1.1** binding и lifecycle; не содержит Boot application,
web-server/MVC starters, `application.yml`, server port или маршрутов.
Наличие JAR на classpath и component scan не включают transport автоматически.

## Явное подключение

Внутри репозитория:

```groovy
dependencies {
    implementation project(':jetty-proxy-spring')
}
```

В существующей конфигурации host:

```java
@Configuration(proxyBeanMethods = false)
@Import(JettyProxyClientConfiguration.class)
class ProxyInfrastructure { }
```

Это создаёт только qualified transport beans:

| Bean | Тип и владелец |
| --- | --- |
| `jettyProxyProperties` | Plain `JettyProxyProperties`, полученный из binding adapter |
| `jettyProxyHttpClient` | Запущенный shared `HttpClient`; остановкой управляет context |
| `jettyProxyEngine` | Streaming engine; закрывается до shared client |
| `jettyProxyFetchClient` | Отдельный bounded fetch; закрывается до shared client |

Константы имён доступны в `JettyProxyClientConfiguration`. Посторонний `HttpClient`
приложения не выбирается по типу и не изменяется. Engine и fetch не останавливают
shared client при собственном `close()`; Spring останавливает его после обоих.

## Filter принадлежит приложению

Библиотека не объявляет `FilterRegistrationBean` или Servlet registration.
Приложение сохраняет свои local/maintenance/backend/forbidden проверки, выбирает
обработчик и передаёт ему запрос один раз до чтения тела и async.

Существующий Filter может завершаться таким фрагментом:

```java
ProxyRequestPlan plan = planner.prepare(request); // backend и immutable контекст приложения
if (plan == null) {
    chain.doFilter(request, response);
    return;
}
selectedExecutor.forward(request, response, null, plan);
```

`selectedExecutor` имеет тип `ProxyExchangeExecutor`; выбор старого или нового
транспорта принадлежит host. Feature toggle не реализован. Для простого нового
маршрута можно зарегистрировать готовый `JettyProxyFilter(executor, planner)` с
`asyncSupported=true`, `DispatcherType.REQUEST` и собственными URL patterns.
Его регистрация и порядок находятся в host-конфигурации.

После handoff не продолжайте chain, не пишите competing fallback и не повторяйте
запрос через другой transport. Все вызывающие filters должны поддерживать async;
парсинг параметров формы/parts до proxy потребляет тело. Поставьте proxy boundary
до generic form parsing, сохраняя обработку локальных MVC routes. Authentication,
если она требуется, выполняется до этой границы; Security модуль не добавляет.

## Настройки

Префикс — **`jetty.proxy`**. Собственного файла свойств в JAR нет; host может задать:

```yaml
jetty:
  proxy:
    chunk-bytes: 65536
    max-concurrent: 64
    max-connections-per-destination: 32
    max-queued-requests-per-destination: 32
    exchange-timeout: 30s
    destination-idle-timeout: 60s
    fetch:
      max-concurrent: 8
      decoder-threads: 2
      max-queued: 8
```

Остальные defaults: upstream placeholder `http://127.0.0.1:9000`, metadata limit
16384, selectors 2, connect timeout 3s и socket idle timeout 30s. Placeholder
ничего не маршрутизирует: library engine/fetch получают target от приложения.
Destination TTL удаляет пустые записи назначения после закрытия соединений;
socket idle timeout сам по себе не устраняет их накопление.

Binding adapter `JettyProxySpringProperties` создаёт plain core properties с общей
валидацией. Fetch имеет отдельный бюджет, defaults 8/2/8; concurrency и queue в
Spring adapter дополнительно ограничиваются `jetty.proxy.max-concurrent`.
Это не общий semaphore: streaming и fetch могут одновременно занимать свои
лимиты. Полные fetch bodies, декодирование и String требуют heap-памяти;
выбирайте budgets отдельно от streaming concurrency.

## Явный TLS profile

По умолчанию используется system trust и hostname verification. Дополнительный
профиль выбирается только по имени `jettyProxyTlsProfile`:

```java
@Bean(name = JettyProxyClientConfiguration.TLS_PROFILE_BEAN_NAME)
ProxyTlsProfile proxyTlsProfile() {
    return ProxyTlsProfile.builder("backend-profile-v2")
            .protocols("TLSv1.3", "TLSv1.2")
            .build();
}
```

Trust/key stores и key alias задаются typed builder core API до старта клиента.
Другие TLS profile beans приложения не подхватываются. Для нескольких identities
создайте отдельные qualified clients/engines через `JettyProxyClientFactory`,
задавая профиль каждому; одна identity не заменяется между активными запросами.

## Сборка и проверка host

```bash
/workspace/.setup/gradle :jetty-proxy-spring:build
/workspace/.setup/gradle :jetty-proxy:publishMavenJavaPublicationToVerificationRepository \
  :jetty-proxy-spring:publishMavenJavaPublicationToVerificationRepository
```

Обычный JAR — `build/libs/jetty-proxy-spring-1.0.0.jar`, Maven coordinate —
`dev.example:jetty-proxy-spring:1.0.0`. Verification repositories находятся в
`build/repository` каждого library module; metadata содержит зависимость на core.
Внешнему consumer нужны оба локальных repository либо общий repository публикации.

[Host integration example](../jetty-proxy-integration-example/README.md) запускает
существующий Filter отдельно с настоящими Netty и Jetty handlers и отдельным
resource loader. Проверяются actual library JAR, отсутствие `BOOT-INF`/settings,
wrappers, multipart, raw URI и локальные routes. Это host-proof, не перенос
недоступного портала. Контракты stages, error/body policies и bounded fetch —
в [core README](../jetty-proxy/README.md); результаты —
[IMPLEMENTATION.md](../jetty-proxy/IMPLEMENTATION.md).
