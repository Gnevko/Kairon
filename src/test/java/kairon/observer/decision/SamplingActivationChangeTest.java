package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.semantics.SemanticChangeKind;
import kairon.semantics.SemanticField;
import kairon.semantics.SemanticProvenance;
import kairon.semantics.SemanticSourceRole;
import kairon.semantics.SemanticStateChange;
import kairon.semantics.SemanticValue;
import kairon.semantics.SemanticValueOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three things {@code activeOrganicSampling} can do, and which one is news.
 *
 * <p>Only one of them is Kairon learning a value rather than the game reporting
 * an event, and telling the two apart matters: a reader shown
 * {@code active: false} cannot see whether a sequence just ended or was never
 * running.</p>
 */
final class SamplingActivationChangeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    @Test
    void establishingTheFlagAsInactiveIsSuppressed() {
        assertTrue(DecisionChangeSelector.initialisedToInactive(
                change(SemanticValue.unknown(), false,
                        SemanticChangeKind.ESTABLISHED)
        ));
    }

    @Test
    void aSequenceStartingIsNotSuppressedByThisRule() {
        assertFalse(
                DecisionChangeSelector.initialisedToInactive(
                        change(SemanticValue.ofBoolean(false), true,
                                SemanticChangeKind.UPDATED)
                ),
                "false to true is a sequence starting"
        );
        assertFalse(
                DecisionChangeSelector.initialisedToInactive(
                        change(SemanticValue.unknown(), true,
                                SemanticChangeKind.ESTABLISHED)
                ),
                "even first established as true, a running sequence is a fact"
        );
    }

    @Test
    void aSequenceEndingIsNotSuppressedByThisRule() {
        assertFalse(
                DecisionChangeSelector.initialisedToInactive(
                        change(SemanticValue.ofBoolean(true), false,
                                SemanticChangeKind.UPDATED)
                ),
                "true to false is a sequence ending"
        );
    }

    /** No other field is touched, whatever its value or kind. */
    @Test
    void theRuleAppliesToNoOtherField() {
        assertFalse(DecisionChangeSelector.initialisedToInactive(
                new SemanticStateChange(
                        SemanticField.LANDABLE,
                        SemanticValue.unknown(),
                        SemanticValue.ofBoolean(false),
                        SemanticChangeKind.ESTABLISHED,
                        SemanticValueOrigin.OBSERVATION,
                        provenance()
                )
        ));
    }

    /**
     * The first supercruise entry of a session, end to end.
     *
     * <p>Deselecting the body establishes the flag as inactive for the first
     * time. The turn keeps the event and the flight mode and reports no change
     * at all.</p>
     */
    @Test
    void aFirstSupercruiseEntryReportsNoSamplingChange() throws Exception {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        JsonNode request = JSON.readTree(serializer.serialize(
                factory.create(fixture.inputs(List.of(
                        fixture.graphDisabled("""
                                {"timestamp":"2026-07-30T10:00:00Z",
                                 "event":"SupercruiseEntry",
                                 "StarSystem":"Schieni GG-A c3-84",
                                 "SystemAddress":23155}
                                """)
                ))).request()
        ));

        assertFalse(
                request.has("changes"),
                "learning that nothing is being sampled is not a change"
        );
        assertEquals(
                "A ship entered supercruise from normal space.",
                request.path("events").get(0).path("event").textValue()
        );
        assertEquals(
                "Schieni GG-A c3-84",
                request.path("events").get(0).path("system").textValue()
        );
        assertEquals(
                "SUPERCRUISE",
                request.path("context").path("navigation")
                        .path("flightMode").textValue(),
                "navigation context is untouched by this rule"
        );
    }

    /**
     * A running sequence ending, end to end.
     *
     * <p>A jump ends whatever was being sampled. The event is a jump, so its
     * mechanism does not state the sampling flag, and the transition survives
     * to the model.</p>
     */
    @Test
    void aRunningSequenceEndingSurvivesToTheModel() throws Exception {
        DecisionTurnFixture fixture = new DecisionTurnFixture();
        fixture.inputs(List.of(fixture.graphDisabled("""
                {"timestamp":"2026-07-30T10:00:00Z","event":"ScanOrganic",
                 "ScanType":"Sample","Genus":"$Codex_Ent_Bacterial_Genus_Name;",
                 "Genus_Localised":"Bacteria",
                 "Variant":"$Codex_Ent_Bacterial_01_F_Name;",
                 "Variant_Localised":"Bacterium Bullaris - Red",
                 "SystemAddress":23155,"Body":20}
                """)));

        JsonNode request = JSON.readTree(serializer.serialize(
                factory.create(fixture.inputs(List.of(
                        fixture.graphDisabled("""
                                {"timestamp":"2026-07-30T10:00:05Z",
                                 "event":"FSDJump","StarSystem":"Elsewhere",
                                 "SystemAddress":9001,"JumpDist":24.5}
                                """)
                ))).request()
        ));

        JsonNode sampling = null;
        for (JsonNode change : request.path("changes")) {
            if ("sampling".equals(change.path("subject").textValue())) {
                sampling = change;
            }
        }
        assertTrue(sampling != null, "the sequence ended and that is a fact");
        assertEquals("UPDATED", sampling.path("kind").textValue());
        assertTrue(
                sampling.path("fields").path("active")
                        .path("before").booleanValue()
        );
        assertFalse(
                sampling.path("fields").path("active")
                        .path("after").booleanValue()
        );
    }

    private static SemanticStateChange change(
            SemanticValue before,
            boolean after,
            SemanticChangeKind changeKind
    ) {
        return new SemanticStateChange(
                SemanticField.ACTIVE_ORGANIC_SAMPLING,
                before,
                SemanticValue.ofBoolean(after),
                changeKind,
                SemanticValueOrigin.OBSERVATION,
                provenance()
        );
    }

    private static SemanticProvenance provenance() {
        return new SemanticProvenance(
                1L,
                SemanticSourceRole.NEW,
                "SupercruiseEntry",
                "observation-1"
        );
    }
}
