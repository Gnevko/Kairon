package kairon.semantics;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.UnknownJournalEvent;
import kairon.observer.LlmJournalEventSelection;
import kairon.observer.LlmJournalEventSelection.ObserverInputRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every catalogued journal event type has exactly one semantic disposition.
 *
 * <p>This is the guard that makes coverage a fact rather than a claim: adding a
 * catalogue type without deciding what Kairon does with it breaks the build.</p>
 */
class SemanticDispositionCoverageTest {

    private static final int CATALOG_EVENT_TYPE_COUNT = 272;
    private static final int NEW_ELIGIBLE_COUNT = 112;
    private static final int CONTEXT_ONLY_COUNT = 2;
    private static final int DIAGNOSTIC_ONLY_COUNT = 158;

    private final SemanticAdapterRegistry registry =
            SemanticAdapterRegistry.production();

    @Test
    void everyCatalogTypeResolvesToExactlyOneDisposition() {
        Map<String, SemanticDisposition> dispositions = dispositions();
        assertEquals(CATALOG_EVENT_TYPE_COUNT, dispositions.size());

        Set<String> undecided = new TreeSet<>();
        dispositions.forEach((eventType, disposition) -> {
            if (disposition == null) {
                undecided.add(eventType);
            }
        });
        assertTrue(
                undecided.isEmpty(),
                "catalogue types with no semantic disposition: " + undecided
        );
    }

    @Test
    void dispositionCountsAreExplicitAndAccountForTheWholeCatalog() {
        Map<SemanticDisposition, Integer> counts =
                new EnumMap<>(SemanticDisposition.class);
        for (SemanticDisposition disposition : SemanticDisposition.values()) {
            counts.put(disposition, 0);
        }
        dispositions().values().forEach(disposition ->
                counts.merge(disposition, 1, Integer::sum)
        );

        int total = counts.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        assertEquals(CATALOG_EVENT_TYPE_COUNT, total);

        // Everything outside the selection profile is diagnostic, so this
        // count is pinned to the role accounting and moves only with it.
        assertEquals(
                DIAGNOSTIC_ONLY_COUNT,
                counts.get(SemanticDisposition.DIAGNOSTIC_ONLY)
        );
        // The two declared exception sets are small and named; the rest of the
        // selection profile is covered by adapters.
        assertEquals(
                SemanticDispositions.declaredExceptionCount(),
                counts.get(SemanticDisposition.NO_CRITICAL_STRUCTURED_FACTS)
                        + counts.get(SemanticDisposition
                        .UNRESOLVED_AUTHORITATIVE_SEMANTICS)
        );
        assertEquals(
                NEW_ELIGIBLE_COUNT + CONTEXT_ONLY_COUNT
                        - SemanticDispositions.declaredExceptionCount(),
                counts.get(SemanticDisposition.STRUCTURED)
        );
    }

    @Test
    void selectionRoleAccountingIsUnchanged() {
        int newEligible = 0;
        int contextOnly = 0;
        int diagnosticOnly = 0;
        for (Class<? extends JournalEventObservation> type : catalogTypes()) {
            switch (LlmJournalEventSelection.roleOf(type)) {
                case NEW_ELIGIBLE -> newEligible++;
                case CONTEXT_ONLY -> contextOnly++;
                case DIAGNOSTIC_ONLY -> diagnosticOnly++;
            }
        }
        assertEquals(NEW_ELIGIBLE_COUNT, newEligible);
        assertEquals(CONTEXT_ONLY_COUNT, contextOnly);
        assertEquals(DIAGNOSTIC_ONLY_COUNT, diagnosticOnly);
    }

    @Test
    void modelEligibleTypesAreNeverDiagnosticOnly() {
        for (Class<? extends JournalEventObservation> type : catalogTypes()) {
            ObserverInputRole role = LlmJournalEventSelection.roleOf(type);
            if (role == ObserverInputRole.DIAGNOSTIC_ONLY) {
                continue;
            }
            SemanticDisposition disposition = registry.dispositionOf(type);
            assertNotNull(disposition, type.getSimpleName());
            assertTrue(
                    disposition != SemanticDisposition.DIAGNOSTIC_ONLY,
                    type.getSimpleName()
                            + " is model-eligible and must not be diagnostic"
            );
        }
    }

    @Test
    void everyModelEligibleTypeHasAnAdapterOrADeclaredException() {
        Set<String> missing = new TreeSet<>();
        for (Class<? extends JournalEventObservation> type : catalogTypes()) {
            if (LlmJournalEventSelection.roleOf(type)
                    == ObserverInputRole.DIAGNOSTIC_ONLY) {
                continue;
            }
            SemanticDisposition disposition = registry.dispositionOf(type);
            boolean covered = registry.supports(type)
                    || disposition == SemanticDisposition
                    .NO_CRITICAL_STRUCTURED_FACTS
                    || disposition == SemanticDisposition
                    .UNRESOLVED_AUTHORITATIVE_SEMANTICS;
            if (!covered) {
                missing.add(type.getSimpleName());
            }
        }
        assertTrue(
                missing.isEmpty(),
                "model-eligible types without structured semantics: " + missing
        );
    }

    @Test
    void contextOnlyTypesStayContextOnlyAndAreStructured() {
        for (Class<? extends JournalEventObservation> type
                : LlmJournalEventSelection.contextEventTypes()) {
            assertEquals(
                    ObserverInputRole.CONTEXT_ONLY,
                    LlmJournalEventSelection.roleOf(type),
                    type.getSimpleName() + " must not become NEW"
            );
            assertEquals(
                    SemanticDisposition.STRUCTURED,
                    registry.dispositionOf(type),
                    type.getSimpleName()
                            + " carries the hidden provenance this design "
                            + "exists to recover"
            );
        }
    }

    @Test
    void aTypeOutsideTheCatalogHasNoDisposition() {
        assertNull(
                registry.dispositionOf(UnknownJournalEvent.class),
                "an uncatalogued payload has no recorded decision"
        );
    }

    @Test
    void addingACatalogTypeWithoutADispositionWouldBreakCoverage() {
        // Simulates the guard's purpose without touching the catalogue: a
        // model-eligible type absent from both the adapter registry and the
        // declared exception sets resolves to no disposition at all.
        SemanticAdapterRegistry empty =
                SemanticAdapterRegistry.builder().build();
        List<Class<? extends JournalEventObservation>> modelEligible =
                LlmJournalEventSelection.newEventTypes();
        Class<? extends JournalEventObservation> sample = modelEligible.stream()
                .filter(type -> !SemanticDispositions
                        .unresolvedAuthoritativeSemantics(type))
                .filter(type -> !SemanticDispositions
                        .noCriticalStructuredFacts(type))
                .findFirst()
                .orElseThrow();

        assertNull(
                empty.dispositionOf(sample),
                "an undeclared model-eligible type must fail the guard"
        );
        assertNotNull(
                registry.dispositionOf(sample),
                "the production registry must decide it"
        );
    }

    // ---------------------------------------------------------------------

    private Map<String, SemanticDisposition> dispositions() {
        Map<String, SemanticDisposition> byName = new TreeMap<>();
        for (Class<? extends JournalEventObservation> type : catalogTypes()) {
            byName.put(
                    type.getSimpleName(),
                    registry.dispositionOf(type)
            );
        }
        return byName;
    }

    /**
     * The catalogue is package-private by design; the test reads it
     * reflectively rather than widening production visibility for a test.
     */
    private static List<Class<? extends JournalEventObservation>>
            catalogTypes() {
        return knownEventTypes().stream()
                .map(SemanticDispositionCoverageTest::payloadTypeFor)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends JournalEventObservation> payloadTypeFor(
            String eventType
    ) {
        try {
            Method method = catalog().getDeclaredMethod(
                    "payloadTypeFor",
                    String.class
            );
            method.setAccessible(true);
            return (Class<? extends JournalEventObservation>)
                    method.invoke(null, eventType);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot read catalog payload type for test",
                    exception
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> knownEventTypes() {
        try {
            Method method = catalog().getDeclaredMethod("knownEventTypes");
            method.setAccessible(true);
            return new TreeSet<>((Set<String>) method.invoke(null));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot read catalog event types for test",
                    exception
            );
        }
    }

    private static Class<?> catalog() throws ClassNotFoundException {
        return Class.forName(
                "kairon.observation.journal.JournalEventCatalog"
        );
    }
}
