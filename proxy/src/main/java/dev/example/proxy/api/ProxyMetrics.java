package dev.example.proxy.api;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Агрегированные метрики одного экземпляра прокси, без привязки к системе мониторинга.
 *
 * <p>Набор счётчиков фиксирован: здесь нет меток URI, клиента или целевого сервера, поэтому
 * разнообразие запросов не увеличивает число временных рядов метрик. Транспорт обновляет
 * статистику один раз при допуске/отказе и один раз при завершении обмена, а не на
 * каждом переданном фрагменте тела. Сброс счётчиков не предусмотрен.</p>
 *
 * <p>Методы потокобезопасны, но не обеспечивают идемпотентность: владелец обмена
 * должен вызывать {@link #finished} ровно один раз при окончательном завершении.</p>
 */
public final class ProxyMetrics {
    private final LongAdder admitted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder cancelled = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder totalUploadBytes = new LongAdder();
    private final LongAdder totalDownloadBytes = new LongAdder();
    private final LongAdder totalDurationNanos = new LongAdder();
    private final AtomicLong maxQueuedResponseBytes = new AtomicLong();

    /** Учитывает получение разрешения на обмен, включая последующее ожидание свободного канала. */
    public void admitted() {
        admitted.increment();
    }

    /**
     * Учитывает отказ до запуска асинхронного обмена: перегрузку, запрет процессора
     * или ошибку подготовки. Запрос уже мог получить разрешение, поэтому admitted и rejected
     * не являются непересекающимися категориями. HTTP 5xx также учитывается в failed.
     */
    public void rejected(int status) {
        rejected.increment();
        if (status >= 500) failed.increment();
    }

    /**
     * Учитывает завершение асинхронного обмена, в том числе ошибку или отмену клиентом.
     * completed означает все завершённые обмены, cancelled — отменённую часть
     * этих обменов. failed — общий счётчик 5xx, включая также {@link #rejected}.
     *
     * @param status итоговый HTTP-статус; 0, если при отмене статус не был определён
     * @param wasCancelled обмен прекратился из-за отмены/завершения со стороны контейнера
     * @param uploadedBytes байты, прочитанные из входящего потока Servlet
     * @param downloadedBytes байты, переданные выходящему потоку Servlet; не подтверждение доставки TCP
     * @param durationNanos полная длительность обмена по монотонным часам
     * @param peakQueuedBytes максимальный объём полезных данных в очереди ответа этого обмена
     */
    public void finished(int status, boolean wasCancelled, long uploadedBytes,
                         long downloadedBytes, long durationNanos, long peakQueuedBytes) {
        completed.increment();
        if (wasCancelled) cancelled.increment();
        if (status >= 500) failed.increment();
        // Телеметрия не должна прерывать очистку ресурсов. Некорректная отрицательная величина
        // не уменьшает накопительный счётчик и не превращает завершение в исключение.
        totalUploadBytes.add(Math.max(0, uploadedBytes));
        totalDownloadBytes.add(Math.max(0, downloadedBytes));
        totalDurationNanos.add(Math.max(0, durationNanos));
        maxQueuedResponseBytes.accumulateAndGet(Math.max(0, peakQueuedBytes), Math::max);
    }

    /**
     * Снимок для адаптера Micrometer/JMX/другой системы мониторинга. При параллельных
     * обновлениях поля могут относиться к слегка разным моментам времени: LongAdder
     * не блокирует все циклы ввода-вывода Netty. После остановки обновляющих потоков
     * значения точны. Текущее число занятых разрешений нельзя вычислять вычитанием этих полей.
     */
    public Snapshot snapshot() {
        return new Snapshot(admitted.sum(), rejected.sum(), completed.sum(), cancelled.sum(),
                failed.sum(), totalUploadBytes.sum(), totalDownloadBytes.sum(),
                totalDurationNanos.sum(), maxQueuedResponseBytes.get());
    }

    /**
     * Неизменяемые накопительные значения с момента создания ProxyMetrics.
     * failed включает HTTP 5xx как при rejected, так и при finished; успешный статус
     * сам по себе не доказывает, что клиент успел получить всё тело.
     */
    public record Snapshot(long admitted, long rejected, long completed, long cancelled,
                           long failed, long totalUploadBytes, long totalDownloadBytes,
                           long totalDurationNanos, long maxQueuedResponseBytes) { }
}
