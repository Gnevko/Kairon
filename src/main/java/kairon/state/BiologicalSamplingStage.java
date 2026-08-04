package kairon.state;

/**
 * Where an active organic-sampling sequence stands.
 *
 * <p>Only the two stages a sequence can be <em>in</em>. {@code Analyse} ends the
 * sequence rather than moving it to a third stage, so there is no terminal
 * constant here: completion is carried by the final trigger's structured fact
 * ({@code processStage: FINAL}, {@code completion: true}), not by residual
 * state.</p>
 */
enum BiologicalSamplingStage {

    /** {@code ScanType: Log} — the first scan of a sequence. */
    START,

    /** {@code ScanType: Sample} — a subsequent scan of the same sequence. */
    PROGRESS
}
