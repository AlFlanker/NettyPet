package dev.example.jettyclient.proxy;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.http.HttpFields;

/** Immutable header changes prepared by an application worker without Servlet access. */
public final class ProxyHeaderPatch {
    private final List<Operation> operations;

    private ProxyHeaderPatch(List<Operation> operations) { this.operations = List.copyOf(operations); }

    public static Builder builder() { return new Builder(); }

    void apply(ProxyHeaderEditor editor) {
        for (Operation operation : operations) {
            switch (operation.kind) {
                case 0 -> editor.set(operation.name, operation.value);
                case 1 -> editor.add(operation.name, operation.value);
                case 2 -> editor.remove(operation.name);
                default -> throw new AssertionError("Unknown header operation");
            }
        }
    }

    private record Operation(int kind, String name, String value) { }

    public static final class Builder {
        private final List<Operation> operations = new ArrayList<>();

        public Builder set(String name, String value) { return add(0, name, value); }
        public Builder add(String name, String value) { return add(1, name, value); }
        public Builder remove(String name) { return add(2, name, null); }

        private Builder add(int kind, String name, String value) {
            // Reuse precisely the synchronous editor's framing and name policy.
            ProxyHeaderEditor validation = new ProxyHeaderEditor(HttpFields.EMPTY);
            try {
                validation.remove(name);
                if (kind != 2) ProxyHeaderEditor.validateValue(value);
            } finally { validation.close(); }
            operations.add(new Operation(kind, name, value));
            return this;
        }

        public ProxyHeaderPatch build() { return new ProxyHeaderPatch(operations); }
    }
}
