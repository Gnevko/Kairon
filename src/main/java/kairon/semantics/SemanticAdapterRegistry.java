package kairon.semantics;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.UnknownJournalEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic registration of semantic adapters by payload class.
 *
 * <p>Keyed on the Java payload type, exactly as
 * {@code BehaviorEventNormalizer} keys its direct rules. No reflection, no
 * scanning, no annotation discovery.</p>
 *
 * <p>Duplicate registration is a programming error and fails fast at build
 * time. An unregistered observation type is a normal runtime condition and
 * yields an explicit unsupported result, never an exception.</p>
 */
public final class SemanticAdapterRegistry {

    private final Map<Class<? extends JournalEventObservation>,
            SemanticEventAdapter> adapters;

    private SemanticAdapterRegistry(
            Map<Class<? extends JournalEventObservation>,
                    SemanticEventAdapter> adapters
    ) {
        this.adapters = Collections.unmodifiableMap(
                new LinkedHashMap<>(adapters)
        );
    }

    /** The registry used by production wiring. */
    public static SemanticAdapterRegistry production() {
        return JournalSemanticAdapters.REGISTRY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<SemanticEventAdapter> adapterFor(
            Class<? extends JournalEventObservation> eventType
    ) {
        Objects.requireNonNull(eventType, "eventType");
        return Optional.ofNullable(adapters.get(eventType));
    }

    public boolean supports(
            Class<? extends JournalEventObservation> eventType
    ) {
        return adapters.containsKey(
                Objects.requireNonNull(eventType, "eventType")
        );
    }

    public Set<Class<? extends JournalEventObservation>> registeredTypes() {
        return adapters.keySet();
    }

    public int size() {
        return adapters.size();
    }

    /**
     * The decision recorded for a catalogued event type.
     *
     * <p>Returns {@code null} only for a payload class that is not in the
     * journal catalogue at all. Every catalogued type resolves to exactly one
     * disposition; a catalogued model-eligible type without an adapter and
     * without a declared exception resolves to {@code null} too, which is a
     * coverage failure the guard is there to catch.</p>
     */
    public SemanticDisposition dispositionOf(
            Class<? extends JournalEventObservation> eventType
    ) {
        Objects.requireNonNull(eventType, "eventType");
        if (eventType == UnknownJournalEvent.class) {
            return null;
        }
        if (SemanticSourceRoleCatalog.roleOf(eventType)
                == SemanticSourceRole.DIAGNOSTIC_ONLY) {
            return SemanticDisposition.DIAGNOSTIC_ONLY;
        }
        if (SemanticDispositions.unresolvedAuthoritativeSemantics(eventType)) {
            return SemanticDisposition.UNRESOLVED_AUTHORITATIVE_SEMANTICS;
        }
        if (SemanticDispositions.noCriticalStructuredFacts(eventType)) {
            return SemanticDisposition.NO_CRITICAL_STRUCTURED_FACTS;
        }
        return adapters.containsKey(eventType)
                ? SemanticDisposition.STRUCTURED
                : null;
    }

    /**
     * Applies the registered adapter, or reports the recorded disposition.
     *
     * <p>Never throws for an unregistered type: replay must not stop because
     * an event has no semantic mapping. A known catalogue type never falls
     * through to {@code NO_SEMANTIC_ADAPTER} — that reason is reserved for a
     * payload the catalogue does not contain.</p>
     */
    public SemanticEventAdapter.Result adapt(
            JournalEventObservation event,
            SemanticProvenance provenance
    ) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(provenance, "provenance");
        SemanticEventAdapter adapter = adapters.get(event.getClass());
        if (adapter != null) {
            SemanticEventAdapter.Result result =
                    adapter.adapt(event, provenance);
            return result == null
                    ? SemanticEventAdapter.Result.empty()
                    : result;
        }
        SemanticDisposition disposition =
                dispositionOf(event.getClass());
        if (disposition == null) {
            return gap(
                    UnresolvedFact.Reason.NO_SEMANTIC_ADAPTER,
                    provenance
            );
        }
        return switch (disposition) {
            case DIAGNOSTIC_ONLY, NO_CRITICAL_STRUCTURED_FACTS ->
                    SemanticEventAdapter.Result.empty();
            case UNRESOLVED_AUTHORITATIVE_SEMANTICS -> gap(
                    UnresolvedFact.Reason
                            .AUTHORITATIVE_SEMANTICS_NOT_ESTABLISHED,
                    provenance
            );
            // Declared STRUCTURED without an adapter is a coverage failure;
            // report it as a gap rather than fabricating facts.
            case STRUCTURED -> gap(
                    UnresolvedFact.Reason.NO_SEMANTIC_ADAPTER,
                    provenance
            );
        };
    }

    private static SemanticEventAdapter.Result gap(
            UnresolvedFact.Reason reason,
            SemanticProvenance provenance
    ) {
        return new SemanticEventAdapter.Result(
                java.util.List.of(),
                java.util.List.of(new UnresolvedFact(
                        SemanticSubject.UNRESOLVED_SUBJECT,
                        reason,
                        provenance
                ))
        );
    }

    /** Fails fast on duplicate registration. */
    public static final class Builder {

        private final Map<Class<? extends JournalEventObservation>,
                SemanticEventAdapter> adapters = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder register(
                Class<? extends JournalEventObservation> eventType,
                SemanticEventAdapter adapter
        ) {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(adapter, "adapter");
            SemanticEventAdapter previous =
                    adapters.putIfAbsent(eventType, adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate semantic adapter for "
                                + eventType.getName()
                );
            }
            return this;
        }

        public SemanticAdapterRegistry build() {
            return new SemanticAdapterRegistry(adapters);
        }
    }
}
