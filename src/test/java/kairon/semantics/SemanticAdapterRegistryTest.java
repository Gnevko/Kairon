package kairon.semantics;

import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.event.travel.DockingGranted;
import kairon.observation.journal.event.travel.DockingRequested;
import kairon.observation.journal.event.travel.FuelScoop;
import kairon.observation.journal.event.inventory.MaterialCollected;
import kairon.observation.journal.event.ship.LaunchDrone;
import kairon.observation.journal.event.travel.ApproachBody;
import kairon.observer.LlmJournalEventSelection;
import kairon.observer.LlmJournalEventSelection.ObserverInputRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticAdapterRegistryTest {

    private static final int CATALOG_EVENT_TYPE_COUNT = 272;

    private final SemanticJournalFixture fixture =
            new SemanticJournalFixture();

    @Test
    void duplicateRegistrationFailsFast() {
        SemanticEventAdapter adapter =
                (event, provenance) -> SemanticEventAdapter.Result.empty();
        SemanticAdapterRegistry.Builder builder =
                SemanticAdapterRegistry.builder()
                        .register(ApproachBody.class, adapter);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> builder.register(ApproachBody.class, adapter)
        );
        assertTrue(failure.getMessage().contains("Duplicate semantic adapter"));
    }

    @Test
    void knownEventMapsDeterministically() {
        String raw = """
                {"timestamp":"2026-07-30T14:00:00Z","event":"ApproachBody",\
                "StarSystem":"Deterministic","SystemAddress":11,\
                "Body":"Deterministic 1","BodyID":3}
                """;
        SemanticFact first = fixture.singleFactOf(raw);
        SemanticFact second = fixture.singleFactOf(raw);

        assertEquals(SemanticSubject.CURRENT_BODY, first.subject());
        assertEquals(SemanticOperation.APPROACHED, first.operation());
        // Provenance differs by busSequence; the meaning must not.
        assertEquals(first.qualifiers(), second.qualifiers());
        assertEquals(first.object(), second.object());
        assertEquals(first.identity(), second.identity());
        assertEquals(first.processStage(), second.processStage());
    }

    @Test
    void knownCatalogTypeNeverFallsBackToNoSemanticAdapter() {
        // Music is catalogued and DIAGNOSTIC_ONLY: it is not model input, so
        // it has a recorded disposition rather than a missing-adapter gap.
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Music",                "MusicTrack":"MainMenu"}
                """);

        assertTrue(envelope.structuredFacts().isEmpty());
        assertTrue(
                envelope.unresolvedFacts().stream().noneMatch(gap ->
                        gap.reason()
                                == UnresolvedFact.Reason.NO_SEMANTIC_ADAPTER),
                "a known catalogue type must never report a missing adapter"
        );
        assertEquals(
                SemanticSourceRole.DIAGNOSTIC_ONLY,
                envelope.sourceRole()
        );
    }

    @Test
    void payloadOutsideTheCatalogueYieldsNoSemanticAdapter() {
        // An unrecognised discriminator becomes UnknownJournalEvent, which is
        // the only case where a missing adapter is the honest answer.
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z",                "event":"NotACataloguedEventType","Value":1}
                """);

        assertTrue(envelope.structuredFacts().isEmpty());
        assertEquals(1, envelope.unresolvedFacts().size());
        assertEquals(
                UnresolvedFact.Reason.NO_SEMANTIC_ADAPTER,
                envelope.unresolvedFacts().getFirst().reason()
        );
    }

    @Test
    void adaptersNeverParseRenderedPresentation() throws Exception {
        // A structural guard: nothing that derives structured meaning may
        // reference the presentation contract, wherever it lives.
        for (String name : List.of(
                "kairon.semantics.JournalSemanticAdapters",
                "kairon.projection.SemanticEnvelopeFactory",
                "kairon.semantics.RawFields"
        )) {
            Class<?> type = Class.forName(name);
            for (Method method : type.getDeclaredMethods()) {
                assertNotEquals(
                        LlmPresentableJournalEvent.LlmEventPresentation.class,
                        method.getReturnType(),
                        name + " must not handle rendered presentation"
                );
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertNotEquals(
                            LlmPresentableJournalEvent.LlmEventPresentation
                                    .class,
                            parameter,
                            name + " must not consume rendered presentation"
                    );
                }
            }
        }
    }

    @Test
    void everyCatalogEventTypeIsAccountedFor() {
        Set<String> catalog = knownEventTypes();
        assertEquals(CATALOG_EVENT_TYPE_COUNT, catalog.size());

        SemanticAdapterRegistry registry =
                SemanticAdapterRegistry.production();
        Set<String> registered = new TreeSet<>();
        for (Class<? extends JournalEventObservation> type
                : registry.registeredTypes()) {
            registered.add(type.getSimpleName());
        }

        // Every registered adapter must target a catalogued event type.
        Set<String> unknown = new TreeSet<>(registered);
        unknown.removeAll(catalog);
        assertTrue(
                unknown.isEmpty(),
                "adapters registered for non-catalog types: " + unknown
        );

        // Accounting closes: covered plus uncovered equals the catalog.
        Set<String> uncovered = new TreeSet<>(catalog);
        uncovered.removeAll(registered);
        assertEquals(
                CATALOG_EVENT_TYPE_COUNT,
                registered.size() + uncovered.size()
        );
        assertEquals(registry.size(), registered.size());
    }

    @Test
    void selectionRoleAccountingClosesAgainstTheCatalog() {
        Set<String> catalog = knownEventTypes();
        int newEligible = 0;
        int contextOnly = 0;
        int diagnosticOnly = 0;
        for (String eventType : catalog) {
            switch (roleOf(eventType)) {
                case NEW_ELIGIBLE -> newEligible++;
                case CONTEXT_ONLY -> contextOnly++;
                case DIAGNOSTIC_ONLY -> diagnosticOnly++;
            }
        }
        assertEquals(112, newEligible);
        assertEquals(2, contextOnly);
        assertEquals(158, diagnosticOnly);
        assertEquals(
                CATALOG_EVENT_TYPE_COUNT,
                newEligible + contextOnly + diagnosticOnly
        );
    }

    @Test
    void presentableEventsWithoutSelectionRoleDoNotBecomeNew() {
        // Having a researched presentation, and now a semantic adapter, is not
        // model eligibility. Phase B must not change event selection.
        List<Class<? extends JournalEventObservation>> presentableOrphans =
                List.of(
                        DockingGranted.class,
                        DockingRequested.class,
                        FuelScoop.class,
                        LaunchDrone.class,
                        MaterialCollected.class
                );
        for (Class<? extends JournalEventObservation> type
                : presentableOrphans) {
            assertTrue(
                    LlmPresentableJournalEvent.class.isAssignableFrom(type),
                    type.getSimpleName() + " should still be presentable"
            );
            assertSame(
                    ObserverInputRole.DIAGNOSTIC_ONLY,
                    LlmJournalEventSelection.roleOf(type),
                    type.getSimpleName() + " must not gain a NEW role"
            );
        }
        assertEquals(
                112,
                LlmJournalEventSelection.NEW_EVENT_TYPE_COUNT
        );
        assertEquals(
                2,
                LlmJournalEventSelection.CONTEXT_EVENT_TYPE_COUNT
        );
    }

    @Test
    void diagnosticEventsDoNotSilentlyProduceGameplayFacts() {
        SemanticObservationEnvelope envelope = fixture.envelopeOf("""
                {"timestamp":"2026-07-30T14:00:00Z","event":"Music",\
                "MusicTrack":"Exploration"}
                """);
        assertFalse(envelope.changedState());
        assertTrue(envelope.structuredFacts().isEmpty());
    }

    private static void assertNotEquals(
            Object unexpected,
            Object actual,
            String message
    ) {
        org.junit.jupiter.api.Assertions.assertNotEquals(
                unexpected,
                actual,
                message
        );
    }

    private static ObserverInputRole roleOf(String eventType) {
        return LlmJournalEventSelection.roleOf(payloadTypeFor(eventType));
    }

    /**
     * The catalog is package-private by design; the test reads it reflectively
     * rather than widening production visibility for a test's benefit.
     */
    private static Class<?> catalog() throws ClassNotFoundException {
        return Class.forName(
                "kairon.observation.journal.JournalEventCatalog"
        );
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
}
