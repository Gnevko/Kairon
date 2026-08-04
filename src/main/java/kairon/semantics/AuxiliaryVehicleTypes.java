package kairon.semantics;

/**
 * What class of vessel an SRV-facing journal record is actually about.
 *
 * <p>Frontier's journal has two auxiliary channels — a fighter one and an SRV
 * one — and the Nomad, a Ship-Launched Vessel, travels through both of them:
 * it is launched as a {@code LaunchFighter}, reports its hold as
 * {@code Cargo(Vessel="SRV")}, is boarded with {@code SRV=true} and is
 * recovered by {@code DockSRV}. Those channel names are evidence about which
 * subsystem the game used, not a statement of what the vessel is, so the
 * domain class is decided here and nowhere else.</p>
 *
 * <p>Three classes, kept apart on purpose. {@code SHIP} is the Commander's own
 * ship, {@code SRV} a conventional Surface Recon Vehicle, {@code SLV} a
 * Ship-Launched Vessel. The concrete model — {@code Nomad} — is a type within a
 * class and never a class of its own: an earlier contract wrote {@code NOMAD}
 * where a class belonged, which made "which sort of vehicle is this" and "which
 * model is it" the same field and answerable only one at a time.</p>
 */
public final class AuxiliaryVehicleTypes {

    public static final String SHIP = "SHIP";
    public static final String SRV = "SRV";
    public static final String SLV = "SLV";
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * The class Kairon used to write for a Nomad, before {@code SLV} existed.
     *
     * <p>Kept for reading only. Persisted behavior-graph occurrences carry the
     * vehicle class they were recorded with, and a graph written before this
     * change still says {@code NOMAD}; {@link #canonicalKind} is what turns
     * that back into a class. Nothing writes it any more.</p>
     */
    public static final String LEGACY_NOMAD = "NOMAD";

    /** Frontier's stable identifier for the Nomad, independent of locale. */
    private static final String NOMAD_RAW_TYPE = "lander01";

    /** Frontier's English label for the same vessel. */
    private static final String NOMAD_LABEL = "Nomad";

    private AuxiliaryVehicleTypes() {
    }

    /**
     * The class and model an SRV-facing record establishes, or null.
     *
     * <p>Both identifying fields are consulted and either is enough, because a
     * journal written in another language carries the stable
     * {@code lander01} while an older one may carry only the label. The
     * comparison is locale-independent for the same reason: {@code lander01}
     * is Frontier's identifier, not text in the player's language.</p>
     *
     * <p>Null when the record identifies nothing at all — no type and no label.
     * A record that names some other SRV is a conventional {@code SRV}: the
     * absence of Nomad evidence is not evidence of a Nomad.</p>
     */
    public static Classification classify(
            String rawType,
            String localisedType
    ) {
        String raw = normalized(rawType);
        String localised = normalized(localisedType);
        if (raw == null && localised == null) {
            return null;
        }
        boolean nomad = NOMAD_RAW_TYPE.equalsIgnoreCase(raw)
                || NOMAD_LABEL.equalsIgnoreCase(localised);
        return new Classification(
                nomad ? SLV : SRV,
                localised != null ? localised : raw
        );
    }

    /**
     * The class a stored value means today.
     *
     * <p>{@code NOMAD} was a class in the previous contract and is a model in
     * this one, so a value read back from a persisted occurrence is mapped to
     * the class it always described. Everything else is returned untouched —
     * this is a rename of one value, not a validator.</p>
     */
    public static String canonicalKind(String storedKind) {
        return LEGACY_NOMAD.equalsIgnoreCase(normalized(storedKind))
                ? SLV
                : storedKind;
    }

    private static String normalized(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * One vessel, as a class and a model.
     *
     * @param kind the domain class: {@link #SRV} or {@link #SLV}
     * @param type the model, in the most speakable form the record carried —
     *             the localised label where there is one, the raw identifier
     *             otherwise, following the same rule every other label in the
     *             pipeline uses. Never null when a classification exists.
     */
    public record Classification(String kind, String type) {
    }
}
