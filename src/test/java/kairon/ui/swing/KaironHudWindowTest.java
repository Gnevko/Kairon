package kairon.ui.swing;

import kairon.ui.KaironGuiHub.ObservationEffectView;
import kairon.ui.KaironGuiHub.ObservationView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KaironHudWindowTest {

    private static final Instant SOURCE_TIME =
            Instant.parse("2026-07-24T18:38:49Z");
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void replayUsesObservedAtWhileOtherModesRetainSourceTimeFallback() {
        assertEquals(
                OBSERVED_AT,
                KaironHudWindow.observationDisplayTime(
                        observation("REPLAY", Optional.of(SOURCE_TIME))
                )
        );
        assertEquals(
                SOURCE_TIME,
                KaironHudWindow.observationDisplayTime(
                        observation("LIVE", Optional.of(SOURCE_TIME))
                )
        );
        assertEquals(
                SOURCE_TIME,
                KaironHudWindow.observationDisplayTime(
                        observation("BOOTSTRAP", Optional.of(SOURCE_TIME))
                )
        );
        assertEquals(
                OBSERVED_AT,
                KaironHudWindow.observationDisplayTime(
                        observation("LIVE", Optional.empty())
                )
        );
    }

    @Test
    void observationEffectsMergeByIdentityAndRetainLatestUseHistory() {
        KaironHudWindow.ObservationTableModel model =
                new KaironHudWindow.ObservationTableModel(3);
        model.append(observation(1L, "LIVE", Optional.of(SOURCE_TIME)));

        assertEquals("OCCURRED_ONLY", model.getValueAt(0, 4));

        model.applyEffect(effect(
                1L,
                "NEW_QUEUED",
                null,
                "2026-07-29T12:00:01Z"
        ));
        model.applyEffect(effect(
                1L,
                "NEW_IN_FLIGHT",
                1L,
                "2026-07-29T12:00:02Z"
        ));
        model.applyEffect(effect(
                1L,
                "NEW_PROCESSED",
                1L,
                "2026-07-29T12:00:03Z"
        ));
        model.applyEffect(effect(
                1L,
                "NEW_IN_FLIGHT",
                2L,
                "2026-07-29T12:00:04Z"
        ));

        assertEquals(
                "NEW_IN_FLIGHT | turn=2",
                model.getValueAt(0, 4)
        );
        assertEquals(4, model.row(0).effects().size());
        assertTrue(
                model.row(0).effectHistoryText()
                        .contains("NEW_PROCESSED | turn=1")
        );
        assertTrue(
                model.row(0).effectHistoryText()
                        .contains("NEW_IN_FLIGHT | turn=2")
        );

        assertEquals(
                -1,
                model.applyEffect(new ObservationEffectView(
                        "observation-1",
                        99L,
                        Instant.parse("2026-07-29T12:00:05Z"),
                        "NEW_FAILED",
                        1L
                ))
        );
        assertEquals(4, model.row(0).effects().size());
    }

    @Test
    void effectBeforeObservationIsMergedAndPendingStateIsBounded() {
        KaironHudWindow.ObservationTableModel model =
                new KaironHudWindow.ObservationTableModel(2);
        model.applyEffect(effect(
                1L,
                "NEW_QUEUED",
                null,
                "2026-07-29T12:00:01Z"
        ));
        model.append(observation(1L, "REPLAY", Optional.of(SOURCE_TIME)));

        assertEquals(0, model.pendingEffectCount());
        assertEquals("NEW_QUEUED", model.getValueAt(0, 4));

        model.applyEffect(effect(
                10L,
                "NEW_QUEUED",
                null,
                "2026-07-29T12:00:10Z"
        ));
        model.applyEffect(effect(
                11L,
                "NEW_QUEUED",
                null,
                "2026-07-29T12:00:11Z"
        ));
        model.applyEffect(effect(
                12L,
                "NEW_QUEUED",
                null,
                "2026-07-29T12:00:12Z"
        ));

        assertEquals(2, model.pendingEffectCount());
        model.append(observation(
                10L,
                "REPLAY",
                Optional.of(SOURCE_TIME)
        ));
        assertEquals("OCCURRED_ONLY", model.getValueAt(1, 4));
    }

    private static ObservationView observation(
            String captureMode,
            Optional<Instant> sourceTime
    ) {
        return observation(1L, captureMode, sourceTime);
    }

    private static ObservationView observation(
            long sequence,
            String captureMode,
            Optional<Instant> sourceTime
    ) {
        return new ObservationView(
                "observation-" + sequence,
                sequence,
                OBSERVED_AT,
                sourceTime,
                "elite-dangerous-journal/gui-test",
                "Journal.gui-test.log:" + sequence,
                captureMode,
                "FSDJump",
                "kairon.observation.journal.event.travel.FSDJump",
                "{\"timestamp\":\"2026-07-24T18:38:49Z\","
                        + "\"event\":\"FSDJump\"}"
        );
    }

    private static ObservationEffectView effect(
            long sequence,
            String effect,
            Long turnSequence,
            String changedAt
    ) {
        return new ObservationEffectView(
                "observation-" + sequence,
                sequence,
                Instant.parse(changedAt),
                effect,
                turnSequence
        );
    }
}
