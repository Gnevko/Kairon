package kairon.behavior.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import kairon.behavior.normalize.NormalizedBehaviorEvent;
import kairon.behavior.normalize.NormalizedEventType;
import kairon.observation.PublishedObservation;
import kairon.observation.status.StatusObservationAdapter.StatusSourcePosition;
import kairon.observation.status.StatusSnapshotObservation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Deterministically derives selected technical state transitions from ordered
 * Elite Dangerous {@code Status.json} snapshots.
 *
 * <p>Each independently observed field establishes its own first-known
 * baseline. Missing fields retain the last-known value and never synthesize an
 * exit. When one GUI-focus change exits one scanner and enters another, exits
 * are emitted before enters.</p>
 *
 * @see <a href="https://hosting.zaonce.net/community/journal/v37/Journal_Manual_v37.pdf">
 * Frontier Player Journal Manual v37, section 14</a>
 */
public final class StatusStateDeltaAdapter {

    static final long LANDING_GEAR_DOWN_FLAG = 4L;

    /**
     * Glide, which the journal never reports.
     *
     * <p>The unpowered descent between orbital cruise and the surface is bit 12
     * of {@code Flags2} and appears in no journal event at all: entering and
     * leaving the orbital-cruise zone are {@code ApproachBody} and
     * {@code LeaveBody}, and the glide between them is only ever a status
     * flag. Journal-only replay therefore cannot reconstruct it, exactly as it
     * cannot reconstruct the scanner modes or the landing gear.</p>
     *
     * @see <a href="https://elite-journal.readthedocs.io/en/latest/Status%20File.html">
     * Status File, Flags2</a>
     */
    static final long GLIDE_FLAG = 4096L;

    static final int FSS_GUI_FOCUS = 9;
    static final int SAA_GUI_FOCUS = 10;

    private Long knownFlags;
    private Long knownFlags2;
    private Integer knownGuiFocus;
    private long lastSnapshotSequence = -1L;
    private String lastObservationId;

    public StatusDeltaBatch adapt(
            PublishedObservation<StatusSnapshotObservation> observation
    ) {
        Objects.requireNonNull(observation, "observation");
        StatusSourcePosition position = requirePosition(observation);
        if (isExactDuplicate(observation.observationId(), position)) {
            return new StatusDeltaBatch(
                    position.snapshotSequence(),
                    List.of()
            );
        }
        requireIncreasingSnapshotSequence(position);

        Instant eventTime = observation.sourceTime()
                .or(observation.payload()::optionalStatusTimestamp)
                .orElse(observation.observedAt());
        List<StatusStateDelta> deltas = new ArrayList<>(4);

        OptionalInt currentGuiFocus =
                observation.payload().optionalGuiFocus();
        if (currentGuiFocus.isPresent()) {
            int current = currentGuiFocus.getAsInt();
            if (knownGuiFocus != null && knownGuiFocus != current) {
                addGuiExits(deltas, eventTime, knownGuiFocus, current);
                addGuiEnters(deltas, eventTime, knownGuiFocus, current);
            }
            knownGuiFocus = current;
        }

        OptionalLong currentFlags = observation.payload().optionalFlags();
        if (currentFlags.isPresent()) {
            long current = currentFlags.getAsLong();
            if (knownFlags != null) {
                boolean previousGearDown = landingGearDown(knownFlags);
                boolean currentGearDown = landingGearDown(current);
                if (previousGearDown != currentGearDown) {
                    add(
                            deltas,
                            currentGearDown
                                    ? NormalizedEventType
                                            .LANDING_GEAR_DEPLOYED
                                    : NormalizedEventType
                                            .LANDING_GEAR_RETRACTED,
                            eventTime,
                            Map.of(
                                    "Flags",
                                    number(current),
                                    "LandingGearDeployed",
                                    JsonNodeFactory.instance.booleanNode(
                                            currentGearDown
                                    ),
                                    "PreviousFlags",
                                    number(knownFlags)
                            )
                    );
                }
            }
            knownFlags = current;
        }

        OptionalLong currentFlags2 = observation.payload().optionalFlags2();
        if (currentFlags2.isPresent()) {
            long current = currentFlags2.getAsLong();
            if (knownFlags2 != null) {
                boolean previousGliding = gliding(knownFlags2);
                boolean currentGliding = gliding(current);
                if (previousGliding != currentGliding) {
                    add(
                            deltas,
                            currentGliding
                                    ? NormalizedEventType.GLIDE_ENTERED
                                    : NormalizedEventType.GLIDE_EXITED,
                            eventTime,
                            Map.of(
                                    "Flags2",
                                    number(current),
                                    "Gliding",
                                    JsonNodeFactory.instance.booleanNode(
                                            currentGliding
                                    ),
                                    "PreviousFlags2",
                                    number(knownFlags2)
                            )
                    );
                }
            }
            knownFlags2 = current;
        }

        lastSnapshotSequence = position.snapshotSequence();
        lastObservationId = observation.observationId();
        return new StatusDeltaBatch(
                position.snapshotSequence(),
                List.copyOf(deltas)
        );
    }

    private boolean isExactDuplicate(
            String observationId,
            StatusSourcePosition position
    ) {
        return position.snapshotSequence() == lastSnapshotSequence
                && Objects.equals(observationId, lastObservationId);
    }

    private void requireIncreasingSnapshotSequence(
            StatusSourcePosition position
    ) {
        if (position.snapshotSequence() > lastSnapshotSequence) {
            return;
        }
        throw new IllegalStateException(
                "status snapshots are out of source order: "
                        + position.snapshotSequence()
                        + " after "
                        + lastSnapshotSequence
        );
    }

    private static StatusSourcePosition requirePosition(
            PublishedObservation<StatusSnapshotObservation> observation
    ) {
        if (!(observation.sourcePosition()
                instanceof StatusSourcePosition position)) {
            throw new IllegalArgumentException(
                    "status delta adapter requires StatusSourcePosition"
            );
        }
        return position;
    }

    private static void addGuiExits(
            List<StatusStateDelta> target,
            Instant eventTime,
            int previous,
            int current
    ) {
        if (previous == FSS_GUI_FOCUS) {
            add(
                    target,
                    NormalizedEventType.FSS_MODE_EXITED,
                    eventTime,
                    guiAttributes(previous, current)
            );
        }
        if (previous == SAA_GUI_FOCUS) {
            add(
                    target,
                    NormalizedEventType.SAA_MODE_EXITED,
                    eventTime,
                    guiAttributes(previous, current)
            );
        }
    }

    private static void addGuiEnters(
            List<StatusStateDelta> target,
            Instant eventTime,
            int previous,
            int current
    ) {
        if (current == FSS_GUI_FOCUS) {
            add(
                    target,
                    NormalizedEventType.FSS_MODE_ENTERED,
                    eventTime,
                    guiAttributes(previous, current)
            );
        }
        if (current == SAA_GUI_FOCUS) {
            add(
                    target,
                    NormalizedEventType.SAA_MODE_ENTERED,
                    eventTime,
                    guiAttributes(previous, current)
            );
        }
    }

    private static Map<String, JsonNode> guiAttributes(
            int previous,
            int current
    ) {
        return Map.of(
                "GuiFocus",
                JsonNodeFactory.instance.numberNode(current),
                "PreviousGuiFocus",
                JsonNodeFactory.instance.numberNode(previous)
        );
    }

    private static JsonNode number(long value) {
        return JsonNodeFactory.instance.numberNode(value);
    }

    private static boolean landingGearDown(long flags) {
        return (flags & LANDING_GEAR_DOWN_FLAG) != 0L;
    }

    private static boolean gliding(long flags2) {
        return (flags2 & GLIDE_FLAG) != 0L;
    }

    private static void add(
            List<StatusStateDelta> target,
            NormalizedEventType eventType,
            Instant eventTime,
            Map<String, JsonNode> attributes
    ) {
        target.add(new StatusStateDelta(
                target.size(),
                new NormalizedBehaviorEvent(
                        eventType,
                        eventTime,
                        attributes,
                        "Status"
                )
        ));
    }

    public record StatusDeltaBatch(
            long snapshotSequence,
            List<StatusStateDelta> deltas
    ) {

        public StatusDeltaBatch {
            if (snapshotSequence < 0) {
                throw new IllegalArgumentException(
                        "snapshotSequence must be nonnegative"
                );
            }
            deltas = List.copyOf(Objects.requireNonNull(deltas, "deltas"));
            for (int index = 0; index < deltas.size(); index++) {
                if (deltas.get(index).ordinal() != index) {
                    throw new IllegalArgumentException(
                            "status delta ordinals must be contiguous from zero"
                    );
                }
            }
        }
    }

    public record StatusStateDelta(
            int ordinal,
            NormalizedBehaviorEvent normalizedEvent
    ) {

        public StatusStateDelta {
            if (ordinal < 0) {
                throw new IllegalArgumentException(
                        "ordinal must be nonnegative"
                );
            }
            Objects.requireNonNull(normalizedEvent, "normalizedEvent");
        }

        public NormalizedEventType eventType() {
            return normalizedEvent.eventType();
        }
    }

}
