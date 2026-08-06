package kairon.system;

import kairon.semantics.BodyIdentity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What every object in a system has, whatever kind of object it is.
 *
 * <p>Identity, place, and how much is known. The per-kind classifications — a
 * star's type, a planet's class — live on the {@link SystemObject}
 * implementations, because a value that only one kind can have is a blank on
 * every other kind, and a record full of blanks is what this registry replaced.
 * What is here is what a signals record, a survey and a parent chain can all
 * state about a body without knowing what kind of body it is.</p>
 *
 * <p>{@code parents} is the chain the journal gave, immediate parent first, root
 * last. Never derived from a body name.</p>
 *
 * <p>{@code signalCounts} keeps only what a reading positively established,
 * keyed by the category name {@code BodySurveyFacts} normalizes to. An omitted
 * category and a category reported at zero are the same thing — nothing was
 * established there — and neither clears what an earlier reading counted.</p>
 */
public record BodyProfile(
        BodyIdentity identity,
        String name,
        List<BodyParent> parents,
        Double distanceFromArrivalLs,
        Boolean wasDiscovered,
        Boolean wasMapped,
        Boolean wasFootfalled,
        BodyKnowledgeLevel knowledge,
        KnowledgeSource source,
        Map<String, Integer> signalCounts,
        BiologicalSurvey biology
) {

    public BodyProfile {
        identity = Objects.requireNonNull(identity, "identity");
        knowledge = Objects.requireNonNull(knowledge, "knowledge");
        source = Objects.requireNonNull(source, "source");
        parents = List.copyOf(Objects.requireNonNull(parents, "parents"));
        signalCounts = Collections.unmodifiableMap(new TreeMap<>(
                Objects.requireNonNull(signalCounts, "signalCounts")
        ));
        biology = Objects.requireNonNull(biology, "biology");
    }

    /** A body known to exist, with nothing established about it. */
    public static BodyProfile listed(
            BodyIdentity identity,
            List<BodyParent> parents
    ) {
        return new BodyProfile(
                identity,
                null,
                parents,
                null,
                null,
                null,
                null,
                BodyKnowledgeLevel.LISTED,
                KnowledgeSource.OBSERVED,
                Map.of(),
                BiologicalSurvey.EMPTY
        );
    }

    /** The body id alone, for a caller already inside one system. */
    public long bodyId() {
        return identity.bodyId();
    }

    public BodyProfile withName(String value) {
        return value == null || value.isBlank()
                ? this
                : new BodyProfile(
                        identity, value, parents, distanceFromArrivalLs,
                        wasDiscovered, wasMapped, wasFootfalled,
                        knowledge, source, signalCounts, biology
                );
    }

    /**
     * This profile with a parent chain, if one was stated.
     *
     * <p>A record that carries no chain leaves the one already recorded
     * standing: silence about where a body sits is not a claim that it sits
     * nowhere.</p>
     */
    public BodyProfile withParents(List<BodyParent> stated) {
        return stated == null || stated.isEmpty()
                ? this
                : new BodyProfile(
                        identity, name, stated, distanceFromArrivalLs,
                        wasDiscovered, wasMapped, wasFootfalled,
                        knowledge, source, signalCounts, biology
                );
    }

    public BodyProfile withDistanceFromArrivalLs(Double value) {
        return value == null
                ? this
                : new BodyProfile(
                        identity, name, parents, value,
                        wasDiscovered, wasMapped, wasFootfalled,
                        knowledge, source, signalCounts, biology
                );
    }

    public BodyProfile withDiscoveryFlags(
            Boolean discovered,
            Boolean mapped,
            Boolean footfalled
    ) {
        return new BodyProfile(
                identity, name, parents, distanceFromArrivalLs,
                discovered == null ? wasDiscovered : discovered,
                mapped == null ? wasMapped : mapped,
                footfalled == null ? wasFootfalled : footfalled,
                knowledge, source, signalCounts, biology
        );
    }

    /** This profile at the higher of its level and the stated one. */
    public BodyProfile withKnowledge(BodyKnowledgeLevel level) {
        BodyKnowledgeLevel raised = BodyKnowledgeLevel.max(knowledge, level);
        return raised == knowledge
                ? this
                : new BodyProfile(
                        identity, name, parents, distanceFromArrivalLs,
                        wasDiscovered, wasMapped, wasFootfalled,
                        raised, source, signalCounts, biology
                );
    }

    /**
     * This profile with a reading's counts merged in.
     *
     * <p>Additions and corrections upward only. What a reading does not mention
     * it does not clear, because the game reports a signal by counting it and
     * reports nothing by any other means.</p>
     */
    public BodyProfile withSignalCounts(Map<String, Integer> reported) {
        if (reported == null || reported.isEmpty()) {
            return this;
        }
        Map<String, Integer> merged = new TreeMap<>(signalCounts);
        merged.putAll(reported);
        return new BodyProfile(
                identity, name, parents, distanceFromArrivalLs,
                wasDiscovered, wasMapped, wasFootfalled,
                knowledge, source, merged, biology
        );
    }

    public BodyProfile withBiology(BiologicalSurvey survey) {
        return survey == null || survey.equals(biology)
                ? this
                : new BodyProfile(
                        identity, name, parents, distanceFromArrivalLs,
                        wasDiscovered, wasMapped, wasFootfalled,
                        knowledge, source, signalCounts, survey
                );
    }
}
