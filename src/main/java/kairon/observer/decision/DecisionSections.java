package kairon.observer.decision;

/**
 * The four top-level section names of a decision request.
 *
 * <p>One place, so that the serializer, the compactor and the overflow
 * diagnostics cannot drift apart on what a section is called.</p>
 */
public final class DecisionSections {

    public static final String EVENTS = "events";
    public static final String CHANGES = "changes";
    public static final String CONTEXT = "context";
    public static final String TRAJECTORY = "trajectory";

    private DecisionSections() {
    }
}
