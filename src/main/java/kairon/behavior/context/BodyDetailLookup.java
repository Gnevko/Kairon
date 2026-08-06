package kairon.behavior.context;

/**
 * What is established about a body, asked for by identity.
 *
 * <p>Handed to the graph for the observation being applied, so that building an
 * occurrence's context is a read of one immutable answer rather than a late
 * call into a live service. The graph never holds the thing that answers.</p>
 *
 * <p>The system is part of the question. A body id repeats across systems, and
 * an answer about the wrong system is worse than no answer — so a lookup that
 * describes another system returns {@link BodyDetail#UNKNOWN} rather than
 * whatever it holds under that id.</p>
 */
@FunctionalInterface
public interface BodyDetailLookup {

    /** Nothing is established about any body. */
    BodyDetailLookup NONE =
            (systemAddress, bodyId) -> BodyDetail.UNKNOWN;

    /**
     * What is established about this body, never null.
     *
     * @param systemAddress the system the body is in, or null when no record
     *                      has said which
     * @param bodyId        the body, or null when none is selected
     */
    BodyDetail detailOf(Long systemAddress, Long bodyId);
}
