package kairon.system;

import java.util.Locale;

/**
 * What kind of thing a body orbits, as the journal itself names it.
 *
 * <p>Every {@code Scan} carries a {@code Parents} array of one-key objects whose
 * key is the kind and whose value is the body id — {@code {"Planet": 4}}. The
 * chain runs from the immediate parent to the root of the system, so a body's
 * place is stated rather than derived. Nothing here reads a body name.</p>
 *
 * <p>{@link #BARYCENTRE} is spelled {@code Null} on the wire, which is the
 * game's way of saying the parent is a centre of mass and not an object. It is
 * renamed here because a value called "null" beside real nulls is a trap.</p>
 *
 * <p>{@link #UNKNOWN} is a kind this build has not seen. It keeps the body id,
 * because where a body sits is worth knowing even when what it sits under has no
 * name here yet.</p>
 */
public enum ParentKind {

    BARYCENTRE,
    STAR,
    PLANET,
    RING,
    UNKNOWN;

    /** The kind a {@code Parents} entry names, never null. */
    public static ParentKind of(String journalName) {
        if (journalName == null) {
            return UNKNOWN;
        }
        return switch (journalName.strip().toUpperCase(Locale.ROOT)) {
            case "NULL" -> BARYCENTRE;
            case "STAR" -> STAR;
            case "PLANET" -> PLANET;
            case "RING" -> RING;
            default -> UNKNOWN;
        };
    }

    /** What an object of this kind is, before anything is scanned. */
    public SystemObjectKind objectKind() {
        return switch (this) {
            case BARYCENTRE -> SystemObjectKind.BARYCENTRE;
            case STAR -> SystemObjectKind.STAR;
            case PLANET -> SystemObjectKind.PLANET;
            case RING -> SystemObjectKind.RING;
            case UNKNOWN -> SystemObjectKind.UNCLASSIFIED;
        };
    }
}
