package kairon.system;

/**
 * Whether what is known about an object was observed or came from elsewhere.
 *
 * <p>Only {@link #OBSERVED} is written today. {@link #EXTERNAL} is the place a
 * future third-party source — a system lookup against Spansh or EDSM — will
 * write, and it exists from the first day rather than from the day that source
 * arrives. Without it the two become indistinguishable the moment the second one
 * is added, and the model would be told the Commander saw something the
 * Commander never saw.</p>
 *
 * <p>The precedence is one-way: an observation replaces an external record, and
 * an external record never replaces an observation. The Commander's own
 * instrument reports the system as it is now; a database reports it as somebody
 * else left it.</p>
 *
 * <p>Recorded per object rather than per field. Per-field provenance is a real
 * problem and a different one — it is the open defect in canonical state, where
 * the origin of a value is decided once per observation — and solving both in
 * one change would make neither reviewable.</p>
 */
public enum KnowledgeSource {

    OBSERVED,
    EXTERNAL
}
