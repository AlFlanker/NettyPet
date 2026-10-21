package dev.example.proxy.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Изменяемые заголовки одного запроса. Имена регистронезависимы, повторные значения
 * хранятся отдельно: например, Set-Cookie нельзя безопасно объединить через запятую.
 * Экземпляр не потокобезопасен и передаётся между этапами только через владельца запроса.
 */
public final class Headers {
    private final Map<String, List<String>> values = new LinkedHashMap<>();

    /** Создаёт изменяемую копию, проверяя имена и каждое значение полей исходной карты. */
    public static Headers copyOf(Map<String, List<String>> source) {
        var headers = new Headers();
        source.forEach((name, entries) -> entries.forEach(value -> headers.add(name, value)));
        return headers;
    }

    /**
     * Создаёт проверенный неизменяемый снимок без промежуточных Headers и изменяемых списков.
     * Разные написания одного имени объединяются в порядке обхода исходной карты.
     */
    static Map<String, List<String>> immutableCopyOf(Map<String, List<String>> source) {
        var snapshot = new LinkedHashMap<String, List<String>>(source.size());
        source.forEach((name, entries) -> {
            String key = normalizeName(name);
            entries.forEach(Headers::validateValue);
            if (entries.isEmpty()) return;
            List<String> previous = snapshot.get(key);
            if (previous == null) snapshot.put(key, List.copyOf(entries));
            else {
                var combined = new ArrayList<String>(previous.size() + entries.size());
                combined.addAll(previous);
                combined.addAll(entries);
                snapshot.put(key, List.copyOf(combined));
            }
        });
        return Collections.unmodifiableMap(snapshot);
    }

    /** Независимая копия без повторной проверки уже проверенных имён и значений. */
    public Headers copy() {
        var copy = new Headers();
        values.forEach((name, entries) -> copy.values.put(name, new ArrayList<>(entries)));
        return copy;
    }

    /** Заменяет все значения поля одним проверенным значением. */
    public Headers set(String name, String value) {
        String key = normalizeName(name);
        validateValue(value);
        var entries = new ArrayList<String>(1);
        entries.add(value);
        values.put(key, entries);
        return this;
    }

    /** Добавляет отдельное значение поля, сохраняя порядок повторяющихся значений. */
    public Headers add(String name, String value) {
        String key = normalizeName(name);
        validateValue(value);
        values.computeIfAbsent(key, ignored -> new ArrayList<>(1)).add(value);
        return this;
    }

    public Headers remove(String name) {
        values.remove(normalizeName(name));
        return this;
    }

    public boolean contains(String name) {
        return values.containsKey(normalizeName(name));
    }

    /** Возвращает неизменяемую копию значений либо пустой список, если поля нет. */
    public List<String> get(String name) {
        List<String> entries = values.get(normalizeName(name));
        return entries == null ? List.of() : List.copyOf(entries);
    }

    /** Первое значение либо null, если заголовка нет. */
    public String first(String name) {
        List<String> entries = values.get(normalizeName(name));
        return entries == null || entries.isEmpty() ? null : entries.getFirst();
    }

    /** Глубоко неизменяемый снимок: вызывающий код не получает доступ к внутренним спискам. */
    public Map<String, List<String>> asMap() {
        return asMap(ignored -> true);
    }

    /**
     * Создаёт неизменяемый снимок выбранных полей за один проход. Условие отбора
     * получает нормализованное имя; изменять Headers во время обхода нельзя.
     */
    public Map<String, List<String>> asMap(Predicate<String> include) {
        Objects.requireNonNull(include, "include");
        var snapshot = new LinkedHashMap<String, List<String>>(values.size());
        values.forEach((name, entries) -> {
            if (include.test(name)) snapshot.put(name, List.copyOf(entries));
        });
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Передаёт каждое имя и значение обработчику без создания снимка карты и списков.
     * Обработчик получает неизменяемые строки; изменять Headers во время обхода нельзя.
     */
    public void forEachValue(BiConsumer<String, String> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        values.forEach((name, entries) -> {
            for (String value : entries) consumer.accept(name, value);
        });
    }

    /** Проверяет допустимость символов имени HTTP-поля и возвращает имя в нижнем регистре. */
    public static String canonicalName(String name) { return normalizeName(name); }

    private static String normalizeName(String name) {
        Objects.requireNonNull(name, "header name");
        if (name.isEmpty()) throw new IllegalArgumentException("Empty HTTP header name");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'A' && c <= 'Z')
                    && !(c >= 'a' && c <= 'z') && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                throw new IllegalArgumentException("Invalid HTTP header name");
            }
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static void validateValue(String value) {
        Objects.requireNonNull(value, "header value");
        // CR/LF и управляющие символы нельзя передавать кодировщику HTTP: это граница
        // защиты от внедрения новой строки заголовков. Произвольный Unicode также
        // не превращаем молча в байты с потерей данных; кодирование — задача расширения.
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f || c > 0xff) {
                throw new IllegalArgumentException("Invalid HTTP header value");
            }
        }
    }
}
