package kairon.observation.journal;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Class-keyed lookup that answers for a variant through the record it belongs
 * to.
 *
 * <p>Journal records are keyed by class in several registries, and those
 * registries ask two different questions. Some ask <em>what kind of record is
 * this</em> — which source role it has, whether it is structurally significant,
 * which adapter derives its facts. Those are properties of the journal event as
 * Frontier defines it, decided once when the event was researched. Others ask
 * <em>which domain event is this</em> — which structural type the graph records,
 * which domain kind the model is told about. Those can differ between two
 * records of the same wire event.</p>
 *
 * <p>A record that dispatches to several classes has to satisfy both. Listing
 * every variant in every registry would answer the second question at the cost
 * of the first: the number of admitted classes would move whenever a record was
 * split, and that number is read as "how many journal events have been
 * reviewed". So the registries that ask the first question keep the one key
 * they always had — the record — and reach a variant through it.</p>
 *
 * <p>One level of interfaces is enough and is deliberate. A variant's direct
 * interface is the sealed group naming its wire event; a deeper walk would
 * start matching {@code LlmPresentableJournalEvent} and turn a missing
 * registration into a silent framework-wide default.</p>
 */
public final class JournalEventLookup {

    private JournalEventLookup() {
    }

    /** Whether the set covers this class, or the record it is a variant of. */
    public static boolean covers(
            Set<Class<? extends JournalEventObservation>> types,
            Class<?> eventType
    ) {
        Objects.requireNonNull(types, "types");
        Objects.requireNonNull(eventType, "eventType");
        if (types.contains(eventType)) {
            return true;
        }
        for (Class<?> declared : eventType.getInterfaces()) {
            if (types.contains(declared)) {
                return true;
            }
        }
        return false;
    }

    /** The value for this class, or for the record it is a variant of. */
    public static <V> V forType(
            Map<Class<? extends JournalEventObservation>, V> byType,
            Class<?> eventType
    ) {
        Objects.requireNonNull(byType, "byType");
        Objects.requireNonNull(eventType, "eventType");
        V exact = byType.get(eventType);
        if (exact != null) {
            return exact;
        }
        for (Class<?> declared : eventType.getInterfaces()) {
            V inherited = byType.get(declared);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }
}
