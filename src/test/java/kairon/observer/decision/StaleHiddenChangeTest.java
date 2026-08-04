package kairon.observer.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kairon.projection.ProjectedObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * An observation the model is not shown can still explain a later one — but
 * only if what it established is still true.
 *
 * <p>A hidden observation's effect waits in the accumulator until a turn closes
 * over it, and other observations move the same field in the meantime. The
 * effect that survived the other rules was then whichever one happened to, not
 * whichever one was true: a restored session establishing
 * {@code flightMode = NORMAL_SPACE} outlived the supercruise jump that replaced
 * it and arrived in a turn whose canonical state already said
 * {@code SUPERCRUISE}. Its presence then displaced the correct value from the
 * context, so the one thing the document said about the flight mode was the
 * wrong thing.</p>
 *
 * <p>Everything here runs the production parser, projector and behaviour graph
 * against isolated temporary storage. The provider is a stub that cannot
 * influence what is built.</p>
 */
final class StaleHiddenChangeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmDecisionRequestFactory factory =
            new LlmDecisionRequestFactory();
    private final JacksonDecisionRequestSerializer serializer =
            new JacksonDecisionRequestSerializer();

    /**
     * A2: a hidden vehicle class that something has since cleared.
     *
     * <p>The same fixture proves both halves of the rule: the vehicle class
     * the restore established is gone by the time the launch opens a turn and
     * is dropped, while where the Commander is sitting is still true and
     * survives — without an {@code eventId}, exactly as before.</p>
     */
    @Test
    void aHiddenVehicleClassThatWasClearedDoesNotReachTheModel(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            pipeline.journal(loadGame());
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:01Z","event":"Location",
                     "StarSystem":"Restore A","SystemAddress":2001,
                     "Body":"Restore A 1","BodyID":5,"BodyType":"Planet",
                     "Docked":false,"InSRV":true}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:05Z",
                     "event":"LaunchFighter","Loadout":"base","ID":13,
                     "PlayerControlled":true}
                    """);
            pipeline.settleProjection();

            JsonNode request = requestFor(pipeline, "LaunchFighter");
            String serialized = request.toString();
            assertFalse(
                    serialized.contains("\"kind\":{\"after\":\"SRV\"}"),
                    "the launch cleared the vehicle class: " + serialized
            );
            assertEquals(
                    List.of("commander"),
                    subjects(request),
                    "only the fact that is still true survives"
            );
            assertEquals(
                    "SRV",
                    request.path("changes").get(0).path("fields")
                            .path("presence").path("after").textValue()
            );
            assertFalse(
                    request.path("changes").get(0).has("eventId"),
                    "a hidden observation established it, and says so by "
                            + "carrying no event id"
            );
        }
    }

    /**
     * A3: a hidden change that is still current is still sent.
     *
     * <p>The guard against the fix becoming a blanket deletion of everything a
     * hidden observation established.</p>
     */
    @Test
    void aHiddenChangeThatIsStillCurrentSurvives(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            pipeline.journal(loadGame());
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:01Z","event":"Location",
                     "StarSystem":"Restore A","SystemAddress":2001,
                     "Body":"Restore A 1","BodyID":5,"BodyType":"Planet",
                     "Docked":false,"OnFoot":true}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:05Z","event":"ApproachBody",
                     "StarSystem":"Restore A","SystemAddress":2001,
                     "Body":"Restore A 1","BodyID":5}
                    """);
            pipeline.settleProjection();

            JsonNode request = requestFor(pipeline, "ApproachBody");
            JsonNode change = request.path("changes").get(0);
            assertEquals("navigation", change.path("subject").textValue());
            assertEquals(
                    "NORMAL_SPACE",
                    change.path("fields").path("flightMode")
                            .path("after").textValue(),
                    "nothing has moved the flight mode since the restore"
            );
            assertFalse(change.has("eventId"));
        }
    }

    /**
     * A4: two hidden observations move one field, and only the survivor is
     * sent.
     */
    @Test
    void onlyTheHiddenValueThatSurvivedIsSent(@TempDir Path directory)
            throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            pipeline.journal(loadGame());
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:01Z","event":"Location",
                     "StarSystem":"Restore A","SystemAddress":2001,
                     "Docked":true,"StationName":"Test Station"}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:02Z","event":"Location",
                     "StarSystem":"Restore B","SystemAddress":2002,
                     "Docked":false}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:05Z","event":"ApproachBody",
                     "StarSystem":"Restore B","SystemAddress":2002,
                     "Body":"Restore B 1","BodyID":7}
                    """);
            pipeline.settleProjection();

            JsonNode request = requestFor(pipeline, "ApproachBody");
            String serialized = request.toString();
            assertEquals(
                    1,
                    flightModeChanges(request).size(),
                    "one field, one statement about it: " + serialized
            );
            assertEquals(
                    "NORMAL_SPACE",
                    flightModeChanges(request).getFirst(),
                    "the establishment of DOCKED did not survive being "
                            + "replaced: " + serialized
            );
            assertFalse(
                    serialized.contains("\"after\":\"DOCKED\""),
                    "nothing asserts the replaced value: " + serialized
            );
        }
    }

    /**
     * A5: a change one of this turn's own events caused is never reconciled
     * away.
     *
     * <p>The jump establishes that the Commander is in the ship; the launch
     * that follows it in the same batch clears the vehicle class outright. The
     * jump's statement is no longer the final value — and it stays, because it
     * is attributed: {@code eventId} says whose step it was, and an event of a
     * batch really can report a step a later event moved on from.</p>
     */
    @Test
    void aTriggerOwnedChangeSurvivesEvenWhenLaterReplaced(
            @TempDir Path directory
    ) throws Exception {
        try (DecisionProductionPipeline pipeline =
                     new DecisionProductionPipeline(directory)) {
            pipeline.journal(loadGame());
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:01Z","event":"FSDJump",
                     "StarSystem":"Schieni","SystemAddress":23155,
                     "JumpDist":8.5,"FuelUsed":0.4,"FuelLevel":30.2}
                    """);
            pipeline.journal("""
                    {"timestamp":"2026-07-30T10:00:02Z",
                     "event":"LaunchFighter","Loadout":"base","ID":13,
                     "PlayerControlled":true}
                    """);
            pipeline.settleProjection();

            List<ProjectedObservation> triggers = pipeline.capturedTriggers();
            JsonNode request = read(serializer.serialize(factory.create(
                    pipeline.inputsFor(List.of(
                            triggers.get(triggers.size() - 2),
                            triggers.getLast()
                    ))
            ).request()));

            assertEquals(
                    List.of(
                            "A ship jumped from one star system to another.",
                            "A vehicle was launched from the ship."
                    ),
                    descriptions(request)
            );
            JsonNode vehicle = changeFor(request, "vehicle");
            assertEquals(
                    1,
                    vehicle.path("eventId").intValue(),
                    "the jump owns it, and says so"
            );
            assertEquals(
                    "SHIP",
                    vehicle.path("fields").path("kind").path("after")
                            .textValue(),
                    "the launch has since cleared the class, and that does "
                            + "not delete what the jump reported"
            );
        }
    }

    // ------------------------------------------------------------- fixtures

    private static String loadGame() {
        return """
                {"timestamp":"2026-07-30T10:00:00Z","event":"LoadGame",
                 "FID":"F12345678","ShipID":9,"Ship":"explorer_nx",
                 "ShipName":"Wanderer"}
                """;
    }

    // -------------------------------------------------------------- reading

    private JsonNode requestFor(
            DecisionProductionPipeline pipeline,
            String payloadSimpleName
    ) {
        ProjectedObservation wanted = pipeline.capturedTriggers().stream()
                .filter(projected -> projected.trigger().payload().getClass()
                        .getSimpleName().equals(payloadSimpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        payloadSimpleName + " never became a trigger"
                ));
        for (ProjectedObservation trigger : pipeline.capturedTriggers()) {
            if (trigger.busSequence() >= wanted.busSequence()) {
                break;
            }
            pipeline.inputsFor(List.of(trigger));
        }
        return read(serializer.serialize(factory.create(
                pipeline.inputsFor(List.of(wanted))
        ).request()));
    }

    private static List<String> subjects(JsonNode request) {
        List<String> subjects = new ArrayList<>();
        request.path("changes").forEach(change ->
                subjects.add(change.path("subject").textValue()));
        return List.copyOf(subjects);
    }

    private static List<String> descriptions(JsonNode request) {
        List<String> descriptions = new ArrayList<>();
        request.path("events").forEach(event ->
                descriptions.add(event.path("event").textValue()));
        return List.copyOf(descriptions);
    }

    private static List<String> flightModeChanges(JsonNode request) {
        List<String> values = new ArrayList<>();
        request.path("changes").forEach(change -> {
            JsonNode after = change.path("fields").path("flightMode")
                    .path("after");
            if (!after.isMissingNode()) {
                values.add(after.textValue());
            }
        });
        return List.copyOf(values);
    }

    private static JsonNode changeFor(JsonNode request, String subject) {
        for (JsonNode change : request.path("changes")) {
            if (subject.equals(change.path("subject").textValue())) {
                return change;
            }
        }
        throw new AssertionError(
                "no change for " + subject + ": " + request
        );
    }

    private static JsonNode read(String serialized) {
        try {
            return JSON.readTree(serialized);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
