package kairon.system;

/**
 * How much has been established about one body, as a ladder that only rises.
 *
 * <p>The three rungs are the two scanning instruments and what comes before
 * them. {@link #LISTED} is a body known to be there — named in another body's
 * parent chain, or filed under a signals record — with nothing established about
 * it. {@link #SCANNED} is a {@code Scan}: the classification, the flags and the
 * measurements. {@link #MAPPED} is a completed surface survey.</p>
 *
 * <p>Monotonic on purpose. A later reading may add facts and may correct a
 * value, but nothing lowers the rung: the game does not un-map a body, and a
 * second scan of a mapped body is not a loss of the survey. {@link #max} is the
 * only way the level moves.</p>
 *
 * <p>Not graded by {@code ScanType}. An {@code AutoScan}, a {@code Basic} scan
 * and a {@code Detailed} one all state what the body is; grading them would be a
 * second opinion about a record the parser has already read, and the difference
 * they really make is which fields are present, which the fields themselves
 * say.</p>
 *
 * <p>Landing is not a rung. Standing on a body reveals nothing further about it,
 * so footfall stays an attribute of the body and not a step on a ladder about
 * how much is known.</p>
 */
public enum BodyKnowledgeLevel {

    LISTED,
    SCANNED,
    MAPPED;

    /** The higher of two levels; the only direction this value moves in. */
    public static BodyKnowledgeLevel max(
            BodyKnowledgeLevel first,
            BodyKnowledgeLevel second
    ) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.compareTo(second) >= 0 ? first : second;
    }
}
