package kairon.observer.decision;

/**
 * Deterministic limits for one decision turn.
 *
 * <p>{@code maxSerializedCharacters} is counted in <strong>Java String
 * characters</strong> — UTF-16 code units as returned by
 * {@code String.length()}. It is not Unicode code points, not UTF-8 bytes and
 * emphatically not tokens: a character budget bounds the document, never the
 * request, and no tokenizer measurement exists in this repository.</p>
 *
 * <p>Two bounds, because two things are bounded. The graph trajectory,
 * active-count and prediction bounds that used to live here bounded sections
 * that no longer reach the model at all.</p>
 *
 * <p>{@link #production()} is the single place the production limits live.
 * They are hardcoded on purpose: a budget that can be raised from a config file
 * is a budget that can be raised instead of understood.</p>
 */
public record DecisionTurnPolicy(
        int maxTriggers,
        int maxSerializedCharacters
) {

    public DecisionTurnPolicy {
        if (maxTriggers < 1) {
            throw new IllegalArgumentException(
                    "maxTriggers must be positive"
            );
        }
        // Deliberately only positive: a compaction test must be able to pin
        // the ladder at budgets far below anything production would use.
        if (maxSerializedCharacters < 1) {
            throw new IllegalArgumentException(
                    "maxSerializedCharacters must be positive"
            );
        }
    }

    /**
     * The production limits: 8 triggers, 16 000 characters.
     *
     * <p>The budget is carried over unchanged from the measured v2 matrix, where
     * 16 000 removed all budget-driven compaction from every measured case and
     * left 34 % headroom over the largest real mandatory content. The decision
     * contract is far smaller than the document that was measured, so the same
     * number is now a wider margin rather than a tighter one, and it stays put
     * so that a change in overflow behaviour would be a change in the content
     * rather than in the limit.</p>
     *
     * <p>Overflow is still possible and is not papered over: the compactor
     * refuses rather than shedding events, and the turn ends as
     * {@code CONTEXT_TOO_LARGE}.</p>
     */
    public static DecisionTurnPolicy production() {
        return new DecisionTurnPolicy(8, 16_000);
    }
}
