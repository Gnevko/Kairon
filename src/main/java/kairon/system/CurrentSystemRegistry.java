package kairon.system;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.observation.PublishedObservation;
import kairon.observation.journal.JournalEventObservation;
import kairon.observation.journal.LlmPresentableJournalEvent;
import kairon.observation.journal.event.exploration.FSSAllBodiesFound;
import kairon.observation.journal.event.exploration.FSSBodySignals;
import kairon.observation.journal.event.exploration.FSSDiscoveryScan;
import kairon.observation.journal.event.exploration.SAAScanComplete;
import kairon.observation.journal.event.exploration.SAASignalsFound;
import kairon.observation.journal.event.exploration.Scan;
import kairon.observation.journal.event.exploration.ScanBaryCentre;
import kairon.observation.journal.event.exploration.ScanOrganic;
import kairon.semantics.BodyIdentity;
import kairon.semantics.BodySurveyFacts;
import kairon.semantics.SystemVisitPolicy;
import kairon.semantics.SystemVisitPolicy.SystemVisitState;
import kairon.semantics.SystemVisitTransition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The star system the Commander is in, kept for as long as the visit lasts.
 *
 * <p>A projection, not a cache. It describes one system: it begins empty, it is
 * filled by the records that state something about that system, and it is
 * discarded when the visit ends. What it replaces kept every body of every
 * system of the run in one flat map, so that returning to a body would not lose
 * what was learned about it — useful, and not a model of anything.</p>
 *
 * <h2>When a visit begins and ends</h2>
 * <p>Not decided here. {@link SystemVisitPolicy} answers it, and the behaviour
 * graph and the observer's novelty memory already ask the same question of the
 * same policy. This is the third asker under the same rule: it asks, it does not
 * re-derive. Three memories of one visit are correct; three definitions of when
 * that visit starts are the defect the policy exists to prevent.</p>
 *
 * <h2>Recording is not admission</h2>
 * <p>Every observation that states something about the system is recorded,
 * whatever the model is told about it. Records declined as triggers, records
 * that are context only, and records read during historical {@code BOOTSTRAP}
 * capture all update it — as canonical state is already updated on bootstrap.
 * That is what lets a Kairon started in the middle of a session know which
 * samples have been collected. Being known and being news are different
 * questions, and this answers only the first.</p>
 *
 * <h2>It records; it does not infer</h2>
 * <p>Every structural fact comes from a record that stated it. The hierarchy is
 * the {@code Parents} chain, the classification is {@code StarType} or
 * {@code PlanetClass}, the totals are the discovery scan's. No value is computed
 * from another value and nothing is read out of a body name.</p>
 *
 * <p>Not thread-safe by itself; the projection coordinator is its only writer,
 * on the single projection thread, and every reader is handed an immutable
 * {@link SystemRegistrySnapshot}.</p>
 */
public final class CurrentSystemRegistry {

    private final Map<Long, SystemObject> objects = new TreeMap<>();

    private boolean visitInProgress;
    private Long visitSystemAddress;
    private String visitCommanderFid;
    private Long visitShipId;

    private String systemName;
    private Integer bodyCount;
    private Integer nonBodyCount;
    private boolean allBodiesFound;

    /**
     * Applies one observation and captures the system as it then stands.
     *
     * <p>One call for both halves, as canonical state does it. The snapshot
     * belongs to the observation that produced it, and there is no way to ask
     * for one later.</p>
     */
    public SystemRegistrySnapshot applyAndCapture(
            PublishedObservation<?> observation,
            VisitIdentity identity
    ) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(identity, "identity");
        if (observation.payload() instanceof JournalEventObservation event) {
            applyVisitBoundary(
                    event,
                    identity,
                    event.raw().parsedJsonObject()
            );
            if (visitInProgress) {
                record(event);
            }
        }
        return capture(observation.busSequence());
    }

    /**
     * The visit ends with the source that was feeding it.
     *
     * <p>The graph completes its episode on replay exhaustion and on close, and
     * a registry that survived either would describe a system nobody is in.
     * The transition is the policy's, so what counts as an ending is not decided
     * here.</p>
     */
    public void endVisit(SystemVisitTransition transition) {
        if (Objects.requireNonNull(transition, "transition").ends()) {
            clearVisit();
        }
    }

    /** The system as it stands now, for a caller holding no observation. */
    public SystemRegistrySnapshot capture(long busSequence) {
        return new SystemRegistrySnapshot(
                busSequence,
                true,
                visitSystemAddress,
                systemName,
                bodyCount,
                nonBodyCount,
                allBodiesFound,
                objects
        );
    }

    // ------------------------------------------------------------- the visit

    private void applyVisitBoundary(
            JournalEventObservation event,
            VisitIdentity identity,
            JsonNode raw
    ) {
        SystemVisitTransition transition = SystemVisitPolicy.of(
                event,
                new SystemVisitState(
                        visitInProgress,
                        visitSystemAddress,
                        visitCommanderFid,
                        visitShipId,
                        identity.systemAddress(),
                        identity.commanderFid(),
                        identity.shipId()
                )
        );
        if (transition.ends()) {
            clearVisit();
            return;
        }
        if (transition.begins()) {
            beginVisit(identity, transition.systemAddress());
        } else if (!visitInProgress) {
            openFirstSystem(identity, raw);
        }
        if (!visitInProgress) {
            return;
        }
        if (visitSystemAddress == null) {
            visitSystemAddress = statedAddress(identity, raw);
        }
        if (identity.systemName() != null) {
            systemName = identity.systemName();
        }
    }

    /**
     * Which system anything has named, canonical state first.
     *
     * <p>A visit can begin before any record has said where it is: a change of
     * vessel is a boundary whether or not the system is known. Leaving the
     * address null would be permanent — the visit is in progress, so nothing
     * would open another — and every reading of the visit would then be refused
     * for belonging to a system this one could not name.</p>
     */
    private static Long statedAddress(VisitIdentity identity, JsonNode raw) {
        return identity.systemAddress() != null
                ? identity.systemAddress()
                : nonNegativeLong(raw, "SystemAddress");
    }

    /**
     * The system the Commander is already in when nothing has opened a visit.
     *
     * <p>Not a second definition of a boundary. The policy defers a restore
     * until the Commander and ship are known, because a visit belongs to a
     * behaviour graph keyed by both — and this registry is keyed by neither. It
     * describes one system, so a system canonical state can name is a system it
     * can describe, and refusing to would lose every reading taken before the
     * identity happened to arrive.</p>
     *
     * <p>Only when nothing is in progress, so it opens once. Every boundary
     * after it is the policy's: the moment the identity does arrive the policy
     * calls it a vessel change, this clears, and what was recorded without an
     * owner does not outlive the visit that had none.</p>
     *
     * <p>The record's own {@code SystemAddress} is used when canonical state has
     * not established one. A scan states which system its reading is of, and a
     * reading dropped because nothing had yet said where the ship was would be
     * lost for the rest of the visit.</p>
     */
    private void openFirstSystem(VisitIdentity identity, JsonNode raw) {
        Long address = statedAddress(identity, raw);
        if (address != null) {
            beginVisit(identity, address);
        }
    }

    private void beginVisit(VisitIdentity identity, Long systemAddress) {
        objects.clear();
        bodyCount = null;
        nonBodyCount = null;
        allBodiesFound = false;
        visitInProgress = true;
        visitCommanderFid = identity.commanderFid();
        visitShipId = identity.shipId();
        visitSystemAddress = systemAddress;
        systemName = identity.systemName();
    }

    private void clearVisit() {
        objects.clear();
        bodyCount = null;
        nonBodyCount = null;
        allBodiesFound = false;
        visitInProgress = false;
        visitSystemAddress = null;
        systemName = null;
    }

    // ---------------------------------------------------------- the records

    private void record(JournalEventObservation event) {
        JsonNode raw = event.raw().parsedJsonObject();
        if (!statesThisSystem(raw)) {
            return;
        }
        if (event instanceof Scan) {
            recordScan(raw);
        } else if (event instanceof ScanBaryCentre) {
            recordBarycentre(raw);
        } else if (event instanceof FSSDiscoveryScan) {
            recordDiscoveryScan(raw);
        } else if (event instanceof FSSAllBodiesFound) {
            recordAllBodiesFound(raw);
        } else if (event instanceof FSSBodySignals) {
            recordSignals(raw, false);
        } else if (event instanceof SAASignalsFound) {
            recordSignals(raw, true);
        } else if (event instanceof SAAScanComplete) {
            recordSurveyComplete(raw);
        } else if (event instanceof ScanOrganic.Analysed) {
            recordCompletedSample(raw);
        } else {
            recordArrival(raw);
        }
    }

    /**
     * A record that says which body the ship is at, and what kind it is.
     *
     * <p>Arrivals, approaches, departures and landings all name a body and some
     * of them carry {@code BodyType} — the closed vocabulary that is the only
     * statement of kind for a body nobody has scanned. Reading it here is what
     * lets the registry answer for a body the Commander flew to without
     * scanning, and it is stated by the record rather than inferred from
     * anything.</p>
     *
     * <p>Applied to any record that names a body in this system, because the
     * fields are the claim: a record without a body id states nothing and is
     * ignored, and one that carries no {@code BodyType} leaves the kind
     * alone.</p>
     */
    private void recordArrival(JsonNode raw) {
        BodyIdentity identity = BodySurveyFacts.bodyIdentity(raw);
        if (identity == null) {
            return;
        }
        SystemObject existing = objects.get(identity.bodyId());
        BodyProfile profile = profileOf(existing, identity)
                .withName(text(raw, "BodyName"))
                .withName(text(raw, "Body"));
        SystemObjectKind stated =
                SystemObjectKind.ofBodyType(textOrNull(raw, "BodyType"));
        if (existing != null
                && existing.kind() != SystemObjectKind.UNCLASSIFIED
                || stated == SystemObjectKind.UNCLASSIFIED) {
            objects.put(identity.bodyId(), reclassified(existing, profile));
            return;
        }
        objects.put(identity.bodyId(), of(stated, profile));
    }

    /**
     * Whether a record is about the system this registry is of.
     *
     * <p>A record that names no system is not admitted. Both the arriving jump
     * and every later reading carry {@code SystemAddress}, so silence here is a
     * record about something else — and a reading filed under the wrong system
     * is worse than a reading dropped.</p>
     */
    private boolean statesThisSystem(JsonNode raw) {
        Long stated = nonNegativeLong(raw, "SystemAddress");
        return stated != null && stated.equals(visitSystemAddress);
    }

    private void recordScan(JsonNode raw) {
        BodyIdentity identity = BodySurveyFacts.bodyIdentity(raw);
        if (identity == null) {
            return;
        }
        List<BodyParent> parents = parentsOf(raw);
        listAncestors(parents);

        SystemObject existing = objects.get(identity.bodyId());
        BodyProfile profile = profileOf(existing, identity)
                .withParents(parents)
                .withName(text(raw, "BodyName"))
                .withDistanceFromArrivalLs(
                        decimal(raw, "DistanceFromArrivalLS")
                )
                .withDiscoveryFlags(
                        flag(raw, "WasDiscovered"),
                        flag(raw, "WasMapped"),
                        flag(raw, "WasFootfalled")
                )
                .withKnowledge(BodyKnowledgeLevel.SCANNED);
        objects.put(identity.bodyId(), classify(raw, existing, profile));
    }

    /**
     * The object a scan describes.
     *
     * <p>Read from which classification the record supplied, exactly as
     * {@code BodySurveyFacts.bodyKind} reads it: a star reading carries
     * {@code StarType}, a planet or moon reading carries {@code PlanetClass},
     * and the two never appear together. A reading with neither — a belt
     * cluster's, for instance — states no kind, so an object already classified
     * keeps its class and an unknown one stays unclassified.</p>
     */
    private SystemObject classify(
            JsonNode raw,
            SystemObject existing,
            BodyProfile profile
    ) {
        if (!text(raw, "StarType").isEmpty()) {
            return new StarBody(
                    profile,
                    text(raw, "StarType"),
                    integer(raw, "Subclass"),
                    text(raw, "Luminosity"),
                    decimal(raw, "StellarMass"),
                    decimal(raw, "Radius"),
                    decimal(raw, "AbsoluteMagnitude"),
                    decimal(raw, "Age_MY"),
                    decimal(raw, "SurfaceTemperature")
            );
        }
        if (!text(raw, "PlanetClass").isEmpty()) {
            return new PlanetBody(
                    profile,
                    text(raw, "PlanetClass"),
                    text(raw, "Atmosphere"),
                    text(raw, "AtmosphereType"),
                    text(raw, "Volcanism"),
                    text(raw, "TerraformState"),
                    flag(raw, "Landable"),
                    flag(raw, "TidalLock"),
                    decimal(raw, "MassEM"),
                    decimal(raw, "Radius"),
                    decimal(raw, "SurfaceGravity"),
                    decimal(raw, "SurfaceTemperature"),
                    decimal(raw, "SurfacePressure")
            );
        }
        return existing == null
                ? new UnclassifiedBody(profile)
                : existing.withProfile(profile);
    }

    private void recordBarycentre(JsonNode raw) {
        Long bodyId = nonNegativeLong(raw, "BodyID");
        if (bodyId == null) {
            return;
        }
        BodyIdentity identity = new BodyIdentity(visitSystemAddress, bodyId);
        SystemObject existing = objects.get(bodyId);
        BodyProfile profile = profileOf(existing, identity)
                .withKnowledge(BodyKnowledgeLevel.SCANNED);
        objects.put(bodyId, new Barycentre(profile));
    }

    private void recordDiscoveryScan(JsonNode raw) {
        Integer bodies = integer(raw, "BodyCount");
        Integer nonBodies = integer(raw, "NonBodyCount");
        if (bodies != null) {
            bodyCount = bodies;
        }
        if (nonBodies != null) {
            nonBodyCount = nonBodies;
        }
    }

    private void recordAllBodiesFound(JsonNode raw) {
        allBodiesFound = true;
        Integer count = integer(raw, "Count");
        if (count != null) {
            bodyCount = count;
        }
    }

    private void recordSignals(JsonNode raw, boolean surfaceSurvey) {
        BodyIdentity identity = BodySurveyFacts.bodyIdentity(raw);
        if (identity == null) {
            return;
        }
        SystemObject existing = objects.get(identity.bodyId());
        BodyProfile profile = profileOf(existing, identity)
                .withName(text(raw, "BodyName"))
                .withSignalCounts(BodySurveyFacts.normalizedSignalCounts(raw));
        if (surfaceSurvey) {
            profile = profile
                    .withKnowledge(BodyKnowledgeLevel.MAPPED)
                    .withBiology(profile.biology().withGenera(generaOf(raw)));
        }
        objects.put(identity.bodyId(), reclassified(existing, profile));
    }

    private void recordSurveyComplete(JsonNode raw) {
        BodyIdentity identity = BodySurveyFacts.bodyIdentity(raw);
        if (identity == null) {
            return;
        }
        SystemObject existing = objects.get(identity.bodyId());
        BodyProfile profile = profileOf(existing, identity)
                .withName(text(raw, "BodyName"))
                .withKnowledge(BodyKnowledgeLevel.MAPPED);
        objects.put(identity.bodyId(), reclassified(existing, profile));
    }

    /**
     * A finished sampling sequence, recorded against the body it happened on.
     *
     * <p>{@code ScanOrganic} files its body under {@code Body} rather than
     * {@code BodyID}, so the shared reader does not apply and the id is read
     * here. The genus is kept as the raw identifier: the localised label is what
     * a comment says out loud and never what two organisms are told apart
     * by.</p>
     */
    private void recordCompletedSample(JsonNode raw) {
        Long bodyId = nonNegativeLong(raw, "Body");
        String genus = text(raw, "Genus");
        if (bodyId == null || genus.isEmpty()) {
            return;
        }
        BodyIdentity identity = new BodyIdentity(visitSystemAddress, bodyId);
        SystemObject existing = objects.get(bodyId);
        BodyProfile profile = profileOf(existing, identity);
        objects.put(bodyId, reclassified(
                existing,
                profile.withBiology(profile.biology().withCompleted(genus))
        ));
    }

    // ------------------------------------------------------------- ancestors

    /**
     * Every body a parent chain names, recorded as known to be there.
     *
     * <p>The chain gives each ancestor its own chain for free: what follows a
     * parent in the list is what that parent orbits. So one scan of a moon
     * establishes the moon, its planet and the star both of them orbit — the
     * planet with no properties at all, which is exactly what is true of it.
     * That is what makes "four of fourteen scanned" honest under any order of
     * scanning, and it is the alternative to inventing placeholder nodes.</p>
     */
    private void listAncestors(List<BodyParent> parents) {
        for (int index = 0; index < parents.size(); index++) {
            BodyParent parent = parents.get(index);
            List<BodyParent> ancestry =
                    List.copyOf(parents.subList(index + 1, parents.size()));
            SystemObject existing = objects.get(parent.bodyId());
            BodyProfile profile = profileOf(
                    existing,
                    new BodyIdentity(visitSystemAddress, parent.bodyId())
            ).withParents(ancestry);
            objects.put(
                    parent.bodyId(),
                    stated(existing, profile, parent.kind())
            );
        }
    }

    /**
     * An object of the kind a parent chain named it.
     *
     * <p>A chain states kinds as well as places: {@code {"Planet": 4}} says body
     * four is a planet. So a body first met as somebody's parent is filed under
     * the right class immediately, and one already recorded without a class is
     * reclassified when a chain finally names it. A class already established by
     * a scan is never overwritten by a chain — the scan read the body's own
     * fields and the chain only mentioned it.</p>
     */
    private SystemObject stated(
            SystemObject existing,
            BodyProfile profile,
            ParentKind parentKind
    ) {
        SystemObjectKind statedKind = parentKind.objectKind();
        if (existing != null
                && existing.kind() != SystemObjectKind.UNCLASSIFIED) {
            return existing.withProfile(profile);
        }
        if (statedKind == SystemObjectKind.UNCLASSIFIED) {
            return reclassified(existing, profile);
        }
        return of(statedKind, profile);
    }

    private static SystemObject of(
            SystemObjectKind kind,
            BodyProfile profile
    ) {
        return switch (kind) {
            case STAR -> StarBody.listed(profile);
            case PLANET -> PlanetBody.listed(profile);
            case RING -> new RingBody(profile);
            case BELT_CLUSTER -> new BeltClusterBody(profile);
            case BARYCENTRE -> new Barycentre(profile);
            case UNCLASSIFIED -> new UnclassifiedBody(profile);
        };
    }

    /** The existing object carrying an updated profile, or a new unclassified
     * one. */
    private static SystemObject reclassified(
            SystemObject existing,
            BodyProfile profile
    ) {
        return existing == null
                ? new UnclassifiedBody(profile)
                : existing.withProfile(profile);
    }

    private static BodyProfile profileOf(
            SystemObject existing,
            BodyIdentity identity
    ) {
        return existing == null
                ? BodyProfile.listed(identity, List.of())
                : existing.profile();
    }

    // ---------------------------------------------------------------- reading

    /**
     * The parent chain a record states, immediate parent first.
     *
     * <p>Each entry is a one-key object whose key is the kind and whose value is
     * the body id. An entry this build cannot read is skipped rather than
     * guessed at, and a record carrying no chain states none.</p>
     */
    private static List<BodyParent> parentsOf(JsonNode raw) {
        JsonNode parents = raw == null ? null : raw.get("Parents");
        if (parents == null || !parents.isArray()) {
            return List.of();
        }
        List<BodyParent> chain = new ArrayList<>();
        for (JsonNode entry : parents) {
            if (entry == null || !entry.isObject()) {
                continue;
            }
            entry.fields().forEachRemaining(field -> {
                Long bodyId = nonNegativeLong(entry, field.getKey());
                if (bodyId != null) {
                    chain.add(new BodyParent(
                            ParentKind.of(field.getKey()),
                            bodyId
                    ));
                }
            });
        }
        return List.copyOf(chain);
    }

    /**
     * The genera a surface survey named, identifier to label.
     *
     * <p>The identifier is the key because it is what two readings are compared
     * on. The label is read through the same {@code displayText} the sampling
     * event's own sentence uses, so the one rule about what may be shown is
     * applied in one place — a genus whose only spelling is the game's
     * {@code $Codex_Ent_…} symbol has <em>no</em> label here, rather than the
     * symbol as its label. A missing word is not a missing organism: the genus
     * is still recorded, still counted, and still compared.</p>
     */
    private static Map<String, String> generaOf(JsonNode raw) {
        JsonNode genuses = raw == null ? null : raw.get("Genuses");
        if (genuses == null || !genuses.isArray()) {
            return Map.of();
        }
        Map<String, String> named = new TreeMap<>();
        for (JsonNode entry : genuses) {
            String identifier = text(entry, "Genus");
            if (identifier.isEmpty()) {
                continue;
            }
            named.put(
                    identifier,
                    LlmPresentableJournalEvent.displayText(entry, "Genus")
                            .orElse(null)
            );
        }
        return named;
    }

    private static String textOrNull(JsonNode node, String name) {
        String value = text(node, name);
        return value.isEmpty() ? null : value;
    }

    private static String text(JsonNode node, String name) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(name);
        return value != null && value.isTextual()
                ? value.textValue().strip()
                : "";
    }

    private static Boolean flag(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value != null && value.isBoolean()
                ? value.booleanValue()
                : null;
    }

    private static Long nonNegativeLong(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < 0) {
            return null;
        }
        return value.longValue();
    }

    private static Integer integer(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToInt()
                ? value.intValue()
                : null;
    }

    private static Double decimal(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        if (value == null || !value.isNumber()) {
            return null;
        }
        double measurement = value.doubleValue();
        return Double.isFinite(measurement) ? measurement : null;
    }
}
