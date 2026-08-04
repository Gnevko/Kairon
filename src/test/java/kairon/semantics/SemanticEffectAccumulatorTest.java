package kairon.semantics;

import kairon.observation.ObservationDraft.ObservationCaptureMode;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.ObservationDraft.SourcePosition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded, exactly-once accumulation of the effects between two model turns.
 */
class SemanticEffectAccumulatorTest {

    private static final Instant TIME =
            Instant.parse("2026-07-30T14:00:00Z");
    private static final ObservationSource SOURCE =
            new ObservationSource("elite-journal", "accumulator-test");

    @Test
    void hiddenEffectsBetweenTriggersAreRetainedInBusOrder() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();
        accumulator.record(envelope(1, SemanticSourceRole.NEW, "FSDJump"));
        accumulator.record(envelope(2, SemanticSourceRole.CONTEXT_ONLY, "Scan"));
        accumulator.record(envelope(3, SemanticSourceRole.STATUS, "Status"));
        accumulator.record(envelope(
                4,
                SemanticSourceRole.CONTROL,
                "REPLAY_SOURCE_EXHAUSTED"
        ));
        accumulator.record(envelope(5, SemanticSourceRole.NEW, "Touchdown"));

        SemanticEffectAccumulator.Drained drained =
                accumulator.drainThrough(5);

        assertEquals(
                List.of(1L, 2L, 3L, 4L, 5L),
                busSequences(drained)
        );
        assertEquals(
                List.of(
                        SemanticSourceRole.NEW,
                        SemanticSourceRole.CONTEXT_ONLY,
                        SemanticSourceRole.STATUS,
                        SemanticSourceRole.CONTROL,
                        SemanticSourceRole.NEW
                ),
                drained.envelopes().stream()
                        .map(SemanticObservationEnvelope::sourceRole)
                        .toList()
        );
        assertFalse(drained.bounded());
    }

    @Test
    void effectsBeforeTheFirstTriggerAreNotLost() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();
        accumulator.record(envelope(1, SemanticSourceRole.CONTEXT_ONLY, "Scan"));
        accumulator.record(envelope(2, SemanticSourceRole.STATUS, "Status"));
        accumulator.record(envelope(3, SemanticSourceRole.NEW, "Touchdown"));

        SemanticEffectAccumulator.Drained drained =
                accumulator.drainThrough(3);

        assertEquals(List.of(1L, 2L, 3L), busSequences(drained));
    }

    @Test
    void effectsAfterTheFinalTriggerRemainForTheNextTurn() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();
        accumulator.record(envelope(1, SemanticSourceRole.NEW, "FSDJump"));
        accumulator.record(envelope(2, SemanticSourceRole.CONTEXT_ONLY, "Scan"));
        accumulator.record(envelope(3, SemanticSourceRole.STATUS, "Status"));

        SemanticEffectAccumulator.Drained first =
                accumulator.drainThrough(1);
        assertEquals(List.of(1L), busSequences(first));
        assertEquals(2, accumulator.pendingEnvelopeCount());

        SemanticEffectAccumulator.Drained second =
                accumulator.drainThrough(3);
        assertEquals(List.of(2L, 3L), busSequences(second));
        assertTrue(accumulator.isEmpty());
    }

    @Test
    void drainedEffectsNeverReappear() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();
        accumulator.record(envelope(1, SemanticSourceRole.NEW, "FSDJump"));
        accumulator.record(envelope(2, SemanticSourceRole.CONTEXT_ONLY, "Scan"));

        assertEquals(List.of(1L, 2L), busSequences(
                accumulator.drainThrough(2)
        ));
        assertTrue(accumulator.drainThrough(2).empty());
        assertTrue(accumulator.drainThrough(Long.MAX_VALUE).empty());
    }

    @Test
    void anEmptyTurnDoesNotDestroyPendingEffects() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();
        accumulator.record(envelope(5, SemanticSourceRole.CONTEXT_ONLY, "Scan"));

        // A turn whose final trigger predates the pending effect.
        SemanticEffectAccumulator.Drained drained =
                accumulator.drainThrough(4);

        assertTrue(drained.empty());
        assertEquals(1, accumulator.pendingEnvelopeCount());
        assertEquals(List.of(5L), busSequences(
                accumulator.drainThrough(5)
        ));
    }

    @Test
    void outOfOrderRecordingIsRejected() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();
        accumulator.record(envelope(4, SemanticSourceRole.NEW, "FSDJump"));

        assertThrows(
                IllegalStateException.class,
                () -> accumulator.record(
                        envelope(4, SemanticSourceRole.CONTEXT_ONLY, "Scan")
                )
        );
        assertThrows(
                IllegalStateException.class,
                () -> accumulator.record(
                        envelope(3, SemanticSourceRole.CONTEXT_ONLY, "Scan")
                )
        );
    }

    @Test
    void diagnosticObservationsEnterOnlyWhenTheyChangedState() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();
        accumulator.record(envelope(
                1,
                SemanticSourceRole.DIAGNOSTIC_ONLY,
                "Music"
        ));
        assertTrue(
                accumulator.isEmpty(),
                "crossing the bus is not a reason to enter model context"
        );

        accumulator.record(stateChangingEnvelope(
                2,
                SemanticSourceRole.DIAGNOSTIC_ONLY,
                "Loadout",
                SemanticField.SHIP_NAME,
                SemanticValue.unknown(),
                new SemanticValue.TextValue("Andromeda")
        ));
        assertEquals(1, accumulator.pendingEnvelopeCount());
    }

    @Test
    void memoryBoundCoalescesWithoutLosingGameplayStateChanges() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator(2);
        accumulator.record(stateChangingEnvelope(
                1,
                SemanticSourceRole.CONTEXT_ONLY,
                "Scan",
                SemanticField.SYSTEM_NAME,
                SemanticValue.unknown(),
                new SemanticValue.TextValue("Alpha")
        ));
        accumulator.record(stateChangingEnvelope(
                2,
                SemanticSourceRole.CONTEXT_ONLY,
                "Scan",
                SemanticField.SYSTEM_NAME,
                new SemanticValue.TextValue("Alpha"),
                new SemanticValue.TextValue("Beta")
        ));
        accumulator.record(stateChangingEnvelope(
                3,
                SemanticSourceRole.CONTEXT_ONLY,
                "Scan",
                SemanticField.SYSTEM_NAME,
                new SemanticValue.TextValue("Beta"),
                new SemanticValue.TextValue("Gamma")
        ));

        SemanticEffectAccumulator.Drained drained =
                accumulator.drainThrough(3);

        assertTrue(drained.bounded());
        SemanticSuppression suppression = drained.suppression().orElseThrow();
        assertEquals(
                SemanticSuppression.Reason.MEMORY_BOUND_COALESCING,
                suppression.reason()
        );
        assertEquals(1, suppression.coalescedEnvelopeCount());
        assertEquals(1L, suppression.firstSuppressedBusSequence());
        assertEquals(1L, suppression.lastSuppressedBusSequence());
        // The net transition across the folded span is preserved exactly.
        assertEquals(1, drained.coalescedStateChanges().size());
        SemanticStateChange coalesced =
                drained.coalescedStateChanges().getFirst();
        assertEquals(SemanticField.SYSTEM_NAME, coalesced.field());
        assertEquals(SemanticValue.unknown(), coalesced.before());
        assertEquals(
                new SemanticValue.TextValue("Alpha"),
                coalesced.after(),
                "the folded envelope's own transition stays intact"
        );
        assertEquals(2, drained.envelopes().size());
    }

    @Test
    void aFieldThatRoundTripsProducesNoNetCoalescedChange() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator(1);
        accumulator.record(stateChangingEnvelope(
                1,
                SemanticSourceRole.CONTEXT_ONLY,
                "Scan",
                SemanticField.SYSTEM_NAME,
                new SemanticValue.TextValue("Alpha"),
                new SemanticValue.TextValue("Beta")
        ));
        accumulator.record(stateChangingEnvelope(
                2,
                SemanticSourceRole.CONTEXT_ONLY,
                "Scan",
                SemanticField.SYSTEM_NAME,
                new SemanticValue.TextValue("Beta"),
                new SemanticValue.TextValue("Alpha")
        ));
        accumulator.record(stateChangingEnvelope(
                3,
                SemanticSourceRole.CONTEXT_ONLY,
                "Scan",
                SemanticField.BODY_NAME,
                SemanticValue.unknown(),
                new SemanticValue.TextValue("Alpha 1")
        ));

        SemanticEffectAccumulator.Drained drained =
                accumulator.drainThrough(3);

        assertTrue(drained.coalescedStateChanges().stream().noneMatch(
                change -> change.field() == SemanticField.SYSTEM_NAME
        ), "a value that returned to itself is not a change");
    }

    @Test
    void accumulationIsDeterministicAcrossIdenticalRuns() {
        List<Long> first = busSequences(runScript());
        List<Long> second = busSequences(runScript());
        assertEquals(first, second);
    }

    private static SemanticEffectAccumulator.Drained runScript() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();
        accumulator.record(envelope(1, SemanticSourceRole.NEW, "FSDJump"));
        accumulator.record(envelope(2, SemanticSourceRole.CONTEXT_ONLY, "Scan"));
        accumulator.record(envelope(3, SemanticSourceRole.STATUS, "Status"));
        accumulator.record(envelope(4, SemanticSourceRole.NEW, "Touchdown"));
        return accumulator.drainThrough(4);
    }

    private static List<Long> busSequences(
            SemanticEffectAccumulator.Drained drained
    ) {
        List<Long> sequences = new ArrayList<>();
        drained.envelopes().forEach(
                envelope -> sequences.add(envelope.busSequence())
        );
        return sequences;
    }

    @Test
    void stateChangesSurviveFoldingWhileDroppedFactsAreReported() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator(1);
        accumulator.record(factAndStateEnvelope(1));
        accumulator.record(factAndStateEnvelope(2));

        SemanticEffectAccumulator.Drained drained =
                accumulator.drainThrough(2);

        SemanticSuppression suppression = drained.suppression().orElseThrow();
        assertEquals(
                SemanticSuppression.Reason.MEMORY_BOUND_COALESCING,
                suppression.reason()
        );
        assertEquals(1, suppression.suppressedFactCount());
        assertEquals(1L, suppression.firstSuppressedBusSequence());
        assertEquals(1L, suppression.lastSuppressedBusSequence());

        // The folded envelope's canonical change is still exactly present.
        assertEquals(1, drained.coalescedStateChanges().size());
        assertEquals(
                SemanticField.SYSTEM_NAME,
                drained.coalescedStateChanges().getFirst().field()
        );
    }

    @Test
    void noSuppressionMarkerWhenNothingWasFolded() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator(8);
        accumulator.record(factAndStateEnvelope(1));
        accumulator.record(factAndStateEnvelope(2));

        SemanticEffectAccumulator.Drained drained =
                accumulator.drainThrough(2);

        assertTrue(
                drained.suppression().isEmpty(),
                "a marker must appear only when detail was actually dropped"
        );
        assertFalse(drained.bounded());
        assertEquals(2, drained.envelopes().size());
    }

    @Test
    void suppressionSpanCoversEveryFoldedEnvelope() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator(1);
        for (long sequence = 1; sequence <= 4; sequence++) {
            accumulator.record(factAndStateEnvelope(sequence));
        }

        SemanticSuppression suppression = accumulator.drainThrough(4)
                .suppression()
                .orElseThrow();

        assertEquals(3, suppression.coalescedEnvelopeCount());
        assertEquals(3, suppression.suppressedFactCount());
        assertEquals(1L, suppression.firstSuppressedBusSequence());
        assertEquals(3L, suppression.lastSuppressedBusSequence());
    }

    /**
     * A historical observation's effects are not held for a later turn.
     *
     * <p>It changed canonical state and it carried a structured fact, and
     * neither is a reason to keep it: what it established was already true
     * before Kairon was listening, so it is background rather than news.</p>
     */
    @Test
    void bootstrapEffectsAreNotRetained() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();

        accumulator.record(factAndStateEnvelope(
                1,
                ObservationCaptureMode.BOOTSTRAP
        ));

        assertEquals(0, accumulator.pendingEnvelopeCount());
        assertEquals(0, accumulator.pendingCoalescedChangeCount());
        assertTrue(accumulator.isEmpty());
        assertTrue(accumulator.drainThrough(Long.MAX_VALUE).empty());
    }

    /** Live and replayed effects are held exactly as before. */
    @Test
    void liveAndReplayedEffectsAreRetained() {
        for (ObservationCaptureMode captureMode : List.of(
                ObservationCaptureMode.LIVE,
                ObservationCaptureMode.REPLAY
        )) {
            SemanticEffectAccumulator accumulator =
                    new SemanticEffectAccumulator();

            accumulator.record(factAndStateEnvelope(1, captureMode));

            assertEquals(
                    1,
                    accumulator.pendingEnvelopeCount(),
                    captureMode + " must be retained"
            );
            assertEquals(
                    List.of(1L),
                    busSequences(accumulator.drainThrough(1))
            );
        }
    }

    /**
     * Declining an effect is not the same as never having seen it.
     *
     * <p>A bootstrap envelope still advances the bus-order check, so a later
     * envelope out of order is still refused and the accumulator cannot be made
     * to accept a rewound sequence by putting a bootstrap record in front of
     * it.</p>
     */
    @Test
    void aDeclinedEnvelopeStillHoldsBusOrder() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();
        accumulator.record(factAndStateEnvelope(
                7,
                ObservationCaptureMode.BOOTSTRAP
        ));

        assertThrows(
                IllegalStateException.class,
                () -> accumulator.record(factAndStateEnvelope(
                        6,
                        ObservationCaptureMode.LIVE
                ))
        );
    }

    /**
     * Retention is not visibility.
     *
     * <p>The model is never told about a live {@code CONTEXT_ONLY} record
     * itself, and its effects are exactly what this accumulator exists to carry
     * into the next turn. A rule keyed on whether the model hears about the
     * record would drop them; retention is keyed on capture mode alone.</p>
     */
    @Test
    void modelSilentLiveEffectsAreStillRetained() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();
        SemanticObservationEnvelope contextOnly = envelope(
                1,
                SemanticSourceRole.CONTEXT_ONLY,
                "Scan",
                ObservationCaptureMode.LIVE
        );
        accumulator.record(contextOnly);

        assertEquals(1, accumulator.pendingEnvelopeCount());
        assertEquals(List.of(1L), busSequences(accumulator.drainThrough(1)));
    }

    /**
     * A live diagnostic observation keeps exactly the contract it had.
     *
     * <p>Retained when it changed canonical state, dropped when it merely
     * crossed the bus. Retention did not touch this rule, and if it had, a
     * capture mode would be deciding something only a role decides.</p>
     */
    @Test
    void liveDiagnosticEffectsKeepTheirExistingContract() {
        SemanticEffectAccumulator accumulator =
                new SemanticEffectAccumulator();

        accumulator.record(envelope(
                1,
                SemanticSourceRole.DIAGNOSTIC_ONLY,
                "Music",
                ObservationCaptureMode.LIVE
        ));
        assertTrue(
                accumulator.isEmpty(),
                "crossing the bus is not a reason to enter model context"
        );

        accumulator.record(stateChangingEnvelope(
                2,
                SemanticSourceRole.DIAGNOSTIC_ONLY,
                "Loadout",
                SemanticField.SHIP_NAME,
                SemanticValue.unknown(),
                new SemanticValue.TextValue("Andromeda"),
                ObservationCaptureMode.LIVE
        ));
        assertEquals(1, accumulator.pendingEnvelopeCount());
    }

    private static SemanticObservationEnvelope factAndStateEnvelope(
            long busSequence
    ) {
        return factAndStateEnvelope(
                busSequence,
                ObservationCaptureMode.REPLAY
        );
    }

    /** An envelope carrying both a structured fact and a canonical change. */
    private static SemanticObservationEnvelope factAndStateEnvelope(
            long busSequence,
            ObservationCaptureMode captureMode
    ) {
        SemanticProvenance provenance = new SemanticProvenance(
                busSequence,
                SemanticSourceRole.CONTEXT_ONLY,
                "Scan",
                "obs-" + busSequence
        );
        SemanticFact fact = new SemanticFact.Builder(
                SemanticSubject.CURRENT_BODY,
                SemanticOperation.SCANNED,
                provenance
        ).build();
        return new SemanticObservationEnvelope(
                busSequence,
                SOURCE,
                SemanticSourceRole.CONTEXT_ONLY,
                captureMode,
                ObservationSemantics.retentionOf(captureMode),
                new TestSourcePosition(busSequence),
                Optional.of(TIME),
                TIME,
                "Scan",
                List.of(fact),
                List.of(new SemanticStateChange(
                        SemanticField.SYSTEM_NAME,
                        SemanticValue.ofText("System" + busSequence),
                        SemanticValue.ofText("System" + (busSequence + 1)),
                        SemanticChangeKind.UPDATED,
                        SemanticValueOrigin.OBSERVATION,
                        provenance
                )),
                List.of()
        );
    }

    private static SemanticObservationEnvelope envelope(
            long busSequence,
            SemanticSourceRole role,
            String rawObservationType
    ) {
        return envelope(
                busSequence,
                role,
                rawObservationType,
                ObservationCaptureMode.REPLAY
        );
    }

    /**
     * One envelope as the production factory would build it for this capture
     * mode.
     *
     * <p>Retention comes from {@link ObservationSemantics#retentionOf} rather
     * than being chosen here, so what these tests assert is the rule the
     * pipeline actually applies and not a fixture's opinion of it.</p>
     */
    private static SemanticObservationEnvelope envelope(
            long busSequence,
            SemanticSourceRole role,
            String rawObservationType,
            ObservationCaptureMode captureMode
    ) {
        SemanticProvenance provenance = new SemanticProvenance(
                busSequence,
                role,
                rawObservationType,
                "obs-" + busSequence
        );
        List<UnresolvedFact> gaps = role
                == SemanticSourceRole.DIAGNOSTIC_ONLY
                ? List.of()
                : List.of(new UnresolvedFact(
                        SemanticSubject.UNRESOLVED_SUBJECT,
                        UnresolvedFact.Reason.NO_SEMANTIC_ADAPTER,
                        provenance
                ));
        return new SemanticObservationEnvelope(
                busSequence,
                SOURCE,
                role,
                captureMode,
                ObservationSemantics.retentionOf(captureMode),
                new TestSourcePosition(busSequence),
                Optional.of(TIME),
                TIME,
                rawObservationType,
                List.of(),
                List.of(),
                gaps
        );
    }

    private static SemanticObservationEnvelope stateChangingEnvelope(
            long busSequence,
            SemanticSourceRole role,
            String rawObservationType,
            SemanticField field,
            SemanticValue before,
            SemanticValue after
    ) {
        return stateChangingEnvelope(
                busSequence,
                role,
                rawObservationType,
                field,
                before,
                after,
                ObservationCaptureMode.REPLAY
        );
    }

    private static SemanticObservationEnvelope stateChangingEnvelope(
            long busSequence,
            SemanticSourceRole role,
            String rawObservationType,
            SemanticField field,
            SemanticValue before,
            SemanticValue after,
            ObservationCaptureMode captureMode
    ) {
        SemanticProvenance provenance = new SemanticProvenance(
                busSequence,
                role,
                rawObservationType,
                "obs-" + busSequence
        );
        SemanticChangeKind kind;
        if (!after.known()) {
            kind = SemanticChangeKind.CLEARED;
        } else if (before.known()) {
            kind = SemanticChangeKind.UPDATED;
        } else {
            kind = SemanticChangeKind.ESTABLISHED;
        }
        return new SemanticObservationEnvelope(
                busSequence,
                SOURCE,
                role,
                captureMode,
                ObservationSemantics.retentionOf(captureMode),
                new TestSourcePosition(busSequence),
                Optional.of(TIME),
                TIME,
                rawObservationType,
                List.of(),
                List.of(new SemanticStateChange(
                        field,
                        before,
                        after,
                        kind,
                        SemanticValueOrigin.OBSERVATION,
                        provenance
                )),
                List.of()
        );
    }

    private record TestSourcePosition(long sequence)
            implements SourcePosition {
    }
}
