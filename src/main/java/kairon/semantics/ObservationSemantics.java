package kairon.semantics;

import kairon.observation.ObservationDraft.ObservationCaptureMode;

import java.util.Objects;

/**
 * What one observation means, in the terms more than one layer has to agree on.
 *
 * <p>Pure and stateless. One question is asked of it, and one layer reads the
 * answer: {@link SemanticEffectAccumulator} keeps a {@code RESTORE_ONLY}
 * observation's effects out of every later turn.</p>
 *
 * <p>Nothing else is classified here. Two further classifications used to be —
 * an application mode and a model visibility — and neither ever had a reader:
 * the graph classified through {@code EventSignificancePolicy}, the observer
 * selected through {@code LlmJournalEventSelection}, and both still do. A
 * classification kept alive only by its own tests is a second answer waiting to
 * disagree with the one in force, so it is gone rather than wired up. Moving
 * either decision here is a behaviour change and has to be argued as one.</p>
 */
public final class ObservationSemantics {

    private ObservationSemantics() {
    }

    /**
     * Whether this observation's effects outlive the moment they were applied
     * in.
     *
     * <p>One rule, on capture mode alone. Bootstrap is the replay of a session
     * already in progress: it is read so that Kairon knows where it is, and
     * every fact it establishes was already true before the first turn. Live
     * and replayed records are things that happened while Kairon was listening,
     * and the next turn is entitled to say so.</p>
     *
     * <p>The payload is deliberately not consulted. "The model is not told about
     * this record" and "this record's effects are not news" are different
     * questions: a live {@code CONTEXT_ONLY} observation answers them
     * differently, and folding the two would silently discard exactly the
     * effects the accumulator exists to keep.</p>
     */
    public static EffectRetention retentionOf(
            ObservationCaptureMode captureMode
    ) {
        Objects.requireNonNull(captureMode, "captureMode");
        return captureMode == ObservationCaptureMode.BOOTSTRAP
                ? EffectRetention.RESTORE_ONLY
                : EffectRetention.RETAIN_FOR_TURN;
    }
}
