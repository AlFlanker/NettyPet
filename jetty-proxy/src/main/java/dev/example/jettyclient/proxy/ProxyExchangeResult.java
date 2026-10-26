package dev.example.jettyclient.proxy;

import java.time.Duration;
import java.util.Objects;

/**
 * Итог одного обмена для метрик и tracing без чтения уже завершённого Servlet.
 * uploadedBytes — байты, прочитанные из Servlet, а не гарантия доставки upstream;
 * downloadedBytes — байты успешных downstream write callbacks, без HTTP framing.
 * После commit status сохраняет отправленный HTTP-код, даже при failure тела;
 * до commit ошибка содержит код локального ответа. Результат не подтверждает
 * получение или обработку данных удалённым приложением.
 */
public record ProxyExchangeResult(int status, Throwable failure, long uploadedBytes,
                                  long downloadedBytes, Duration elapsed) {
    public ProxyExchangeResult { Objects.requireNonNull(elapsed, "elapsed"); }

    public boolean success() { return failure == null; }
}
