package kairon.semantics;

import kairon.observation.journal.JournalEventObservation;
import kairon.semantics.SemanticFact.EntityKind;
import kairon.semantics.SemanticFact.EntityRef;
import kairon.semantics.SemanticFact.ProcessStage;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared building blocks for mechanism-oriented semantic adapters.
 *
 * <p>Kept in one place so adapters stay declarative and so no adapter is
 * tempted to reach for rendered prose. Everything here reads the record's own
 * JSON.</p>
 */
final class SemanticAdapterSupport {

    private SemanticAdapterSupport() {
    }

    static RawFields fields(JournalEventObservation event) {
        return RawFields.of(event.raw().parsedJsonObject());
    }

    static SemanticFact.Builder fact(
            SemanticSubject subject,
            SemanticOperation operation,
            SemanticProvenance provenance
    ) {
        return new SemanticFact.Builder(subject, operation, provenance);
    }

    static EntityRef bodyRef(RawFields raw) {
        return new EntityRef(
                EntityKind.BODY,
                raw.identity("BodyID", "BodyID"),
                raw.text("BodyName").or(() -> raw.text("Body")).orElse(null)
        );
    }

    /**
     * A single outcome fact about the commander with an identified object.
     *
     * <p>The workhorse for transaction-shaped mechanisms: something happened,
     * to a named thing, optionally with a measured amount.</p>
     */
    static SemanticFact.Builder commanderOutcome(
            SemanticProvenance provenance,
            SemanticOperation operation,
            EntityKind objectKind,
            RawFields raw,
            String nameField,
            SemanticValue identity
    ) {
        return fact(SemanticSubject.COMMANDER, operation, provenance)
                .object(new EntityRef(
                        objectKind,
                        identity,
                        raw.text(nameField + "_Localised")
                                .or(() -> raw.text(nameField))
                                .orElse(null)
                ))
                .identity(identity)
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    /** A ship-subject outcome, used by damage, repair and ownership events. */
    static SemanticFact.Builder shipOutcome(
            SemanticProvenance provenance,
            SemanticOperation operation
    ) {
        return fact(SemanticSubject.PRIMARY_SHIP, operation, provenance)
                .actor(SemanticSubject.COMMANDER)
                .processStage(ProcessStage.FINAL)
                .completion(Boolean.TRUE);
    }

    /**
     * Turns an affirmative boolean field into explicit negation semantics.
     *
     * <p>{@code null} when the field is absent: an unstated fact is not a
     * negative one.</p>
     */
    static Boolean negationOf(RawFields raw, String name) {
        SemanticValue value = raw.booleanValue(name);
        if (value instanceof SemanticValue.BooleanValue bool) {
            return !bool.value();
        }
        return null;
    }

    /**
     * Records taxi and multicrew participation as explicit gaps.
     *
     * <p>Both fields appear on several travel events and are read for prose
     * today, but the canonical projection models neither, so nothing may be
     * concluded from them.</p>
     */
    static List<UnresolvedFact> vesselContextGaps(
            RawFields raw,
            SemanticProvenance provenance
    ) {
        List<UnresolvedFact> gaps = new ArrayList<>(2);
        if (raw.flag("Taxi")) {
            gaps.add(new UnresolvedFact(
                    SemanticSubject.COMMANDER_PRESENCE,
                    UnresolvedFact.Reason.TAXI_CONTEXT_NOT_MODELLED,
                    provenance
            ));
        }
        if (raw.flag("Multicrew")) {
            gaps.add(new UnresolvedFact(
                    SemanticSubject.COMMANDER_PRESENCE,
                    UnresolvedFact.Reason.MULTICREW_CONTEXT_NOT_MODELLED,
                    provenance
            ));
        }
        return gaps;
    }

    static List<UnresolvedFact> withGap(
            List<UnresolvedFact> gaps,
            UnresolvedFact extra
    ) {
        List<UnresolvedFact> combined = new ArrayList<>(gaps);
        combined.add(extra);
        return combined;
    }
}
