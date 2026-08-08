package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading a serialized request back, for tests that assert on the wire.
 *
 * <p>Two methods, each of which had been copied into every class that needed
 * it: fourteen private {@code read} helpers and seven {@code propertyNames},
 * differing only in which exception they caught and what the parameter was
 * called. Nothing about either is a claim, so nothing is lost by saying them
 * once.</p>
 *
 * <p>The document is read as JSON on purpose. A test that asserts on the
 * request must see what the provider sees, not the object it was built from —
 * a field that is dropped, renamed or emptied during serialization is exactly
 * the kind of defect the contract tests exist to catch.</p>
 */
final class RequestJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RequestJson() {
    }

    /** The serialized request, as a tree. */
    static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Every name the node carries, in order.
     *
     * <p>Order matters and is asserted on: the request is a document a person
     * reads, and its sections arrive in one sequence.</p>
     */
    static List<String> propertyNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }
}
