package com.otr.portal.command.execution;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Общее состояние срока и отмены одной команды, включая допуск, попытки и паузы между ними.
 * Время измеряется монотонными часами. Проверка выполняется на потоке, который запускает
 * попытку или принимает результат: задержка планировщика не разрешает просроченную
 * попытку и не превращает поздний результат в успех.
 */
public final class CommandScope {
    private final long startedAt = System.nanoTime();
    private final long timeoutNanos;
    private final Consumer<CancellationReason> onCancellation;
    private CancellationReason cancellation;
    private boolean decided;

    public CommandScope(Duration timeout, Consumer<CancellationReason> onCancellation) {
        timeoutNanos = validateTimeout(timeout);
        this.onCancellation = Objects.requireNonNull(onCancellation, "onCancellation");
    }

    /** Проверяет представимость бюджета до создания таймера и захвата ресурсов выполнения. */
    static long validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        final long nanos;
        try { nanos = timeout.toNanos(); }
        catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Command timeout must fit a monotonic nanosecond budget", overflow);
        }
        if (nanos < 1_000_000)
            throw new IllegalArgumentException("Command timeout must be at least one millisecond");
        return nanos;
    }

    /** Возвращает остаток общего бюджета; новая попытка не начинает отсчёт заново. */
    public long remainingNanos() {
        return timeoutNanos - (System.nanoTime() - startedAt);
    }

    /**
     * Проверяет срок и отмену перед началом попытки или обработкой её промежуточного результата.
     * При первом обнаружении истёкшего срока фиксирует TIMEOUT и вызывает обработчик отмены.
     */
    public boolean isActive() { return gate(false); }

    /** Разрешает принять итоговый результат, только если срок не истёк и отмена ещё не победила. */
    boolean tryComplete() { return gate(true); }

    /**
     * Под одним монитором выбирает между допустимой работой, итогом и истечением срока.
     * Уведомляет об отмене вне монитора, чтобы обратный вызов не удерживал блокировку состояния.
     */
    private boolean gate(boolean complete) {
        boolean expired = false;
        synchronized (this) {
            if (decided) return false;
            if (remainingNanos() <= 0) {
                cancellation = CancellationReason.TIMEOUT;
                decided = true;
                expired = true;
            } else {
                if (complete) decided = true;
                return true;
            }
        }
        if (expired) onCancellation.accept(CancellationReason.TIMEOUT);
        return false;
    }

    /** Фиксирует первую причину отмены; уже выбранный результат или другая отмена не заменяются. */
    public void cancel(CancellationReason reason) {
        Objects.requireNonNull(reason, "reason");
        synchronized (this) {
            if (decided) return;
            cancellation = reason;
            decided = true;
        }
        onCancellation.accept(reason);
    }

    public synchronized CancellationReason cancellationReason() { return cancellation; }

}
