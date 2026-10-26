package dev.example.jettyclient.proxy;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.jetty.http.HttpFields;

/**
 * Ограниченный редактор отдельной копии заголовков на время вызова расширений.
 * Он не предоставляет исходный Mutable Jetty или Servlet response.
 * Host, framing, hop-by-hop и описание непрозрачного тела контролирует прокси;
 * попытка изменить их означает ошибку расширения.
 */
public final class ProxyHeaderEditor {
    private static final Set<String> RESERVED = Set.of(
            "host", "content-length", "expect", "connection", "keep-alive", "proxy-connection",
            "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
            "content-type", "content-encoding", "content-range");

    private final HttpFields.Mutable fields;
    private final Thread owner = Thread.currentThread();
    private volatile boolean closed;

    ProxyHeaderEditor(HttpFields source) {
        fields = HttpFields.build(source);
    }

    /** Добавляет значение, сохраняя предыдущие; подходит, например, для повторных Set-Cookie. */
    public void add(String name, String value) {
        writable(name);
        validateValue(value);
        fields.add(name, value);
    }

    /** Заменяет все значения заголовка одним значением. */
    public void set(String name, String value) {
        writable(name);
        validateValue(value);
        fields.put(name, value);
    }

    /** Удаляет все значения заголовка без учёта регистра имени. */
    public void remove(String name) {
        writable(name);
        fields.remove(name);
    }

    /** Возвращает неизменяемый снимок текущих значений без учёта регистра имени. */
    public List<String> values(String name) {
        open();
        validateName(name);
        return List.copyOf(fields.getValuesList(name));
    }

    HttpFields freeze() {
        open();
        closed = true;
        return HttpFields.build(fields).asImmutable();
    }

    void close() { closed = true; }

    private void writable(String name) {
        open();
        validateName(name);
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Proxy controls header: " + name);
        }
    }

    private void open() {
        if (closed) throw new IllegalStateException("Header editor is closed");
        if (Thread.currentThread() != owner) throw new IllegalStateException("Header editor belongs to another thread");
    }

    static void validateName(String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Header name is empty");
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (!(ch >= 'a' && ch <= 'z') && !(ch >= 'A' && ch <= 'Z') && !(ch >= '0' && ch <= '9')
                    && "!#$%&'*+-.^_`|~".indexOf(ch) < 0) {
                throw new IllegalArgumentException("Invalid header name");
            }
        }
    }

    static void validateValue(String value) {
        if (value == null) throw new IllegalArgumentException("Header value is null");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\t' && (ch < 0x20 || ch == 0x7f || ch > 0xff)) {
                throw new IllegalArgumentException("Invalid header value");
            }
        }
    }
}
