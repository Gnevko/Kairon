package kairon.ui.swing;

import kairon.semantics.BodyIdentity;
import kairon.system.BodyKnowledgeLevel;
import kairon.system.BodyParent;
import kairon.system.BodyProfile;
import kairon.system.KnowledgeSource;
import kairon.system.ParentKind;
import kairon.system.PlanetBody;
import kairon.system.StarBody;
import kairon.system.SystemObject;
import kairon.system.SystemRegistrySnapshot;
import kairon.system.BiologicalSurvey;
import kairon.ui.swing.SystemRegistryTab.SystemRegistryTableModel;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the registry view shows, and what it refuses to make up.
 *
 * <p>The rows are the parent chains the journal stated, and an unestablished
 * fact stays visibly unestablished. A view that rendered "not scanned" as a
 * zero, or an unmapped body as "0 of 3 collected", would undo on screen exactly
 * the distinction the registry keeps.</p>
 */
final class SystemRegistryTabTest {

    private static final long SYSTEM = 4001L;

    @Test
    void bodiesAreListedUnderTheirParentsAndIndentedByDepth() {
        SystemRegistryTableModel model = new SystemRegistryTableModel();

        model.apply(snapshot(
                barycentreAt(0),
                starAt(1, 0),
                planetAt(4, 1, 0),
                moonAt(5, 4, 1, 0),
                planetAt(6, 1, 0)
        ));

        assertEquals(
                List.of(
                        "body 0",
                        "   body 1",
                        "      body 4",
                        "         Survey Alpha A 2 a",
                        "      body 6"
                ),
                column(model, 0),
                "the chain the journal stated, indented by its own depth"
        );
    }

    /**
     * Neither an unrecorded parent nor a missing chain loses a body.
     *
     * <p>Body 1 names a barycentre the snapshot does not hold; body 9 names
     * nothing at all. Both are shown, because a table that quietly held fewer
     * bodies than the registry does would be worse than one that indents
     * them oddly.</p>
     */
    @Test
    void aBodyWhoseParentIsNotRecordedIsShownAnyway() {
        SystemRegistryTableModel model = new SystemRegistryTableModel();

        model.apply(snapshot(starAt(1, 0), signalsOnlyAt(9)));

        assertEquals(
                List.of("body 1", "body 9"),
                column(model, 0)
        );
    }

    @Test
    void anUnestablishedFactIsBlankRatherThanZero() {
        SystemRegistryTableModel model = new SystemRegistryTableModel();

        model.apply(snapshot(planetAt(4, 1, 0)));

        assertEquals("", model.getValueAt(0, 2), "not scanned is not a level");
        assertNull(model.getValueAt(0, 3), "no distance was reported");
        assertEquals("", model.getValueAt(0, 4), "no signals were counted");
        assertEquals("", model.getValueAt(0, 5), "and nothing was surveyed");
    }

    @Test
    void collectedIsCountedAgainstWhatTheSurveyNamedAndNothingElse() {
        SystemRegistryTableModel model = new SystemRegistryTableModel();
        BodyProfile profile = new BodyProfile(
                new BodyIdentity(SYSTEM, 5),
                "Survey Alpha A 2 a",
                List.of(new BodyParent(ParentKind.PLANET, 4)),
                476.48,
                Boolean.FALSE,
                Boolean.TRUE,
                null,
                BodyKnowledgeLevel.MAPPED,
                KnowledgeSource.OBSERVED,
                Map.of("BIOLOGICAL", 3),
                new BiologicalSurvey(
                        Map.of(
                                "$Codex_Ent_Bacterial_Genus_Name;", "Bacterium",
                                "$Codex_Ent_Tussocks_Genus_Name;", "Tussock",
                                "$Codex_Ent_Fonticulus_Genus_Name;", "Fonticulua"
                        ),
                        Set.of("$Codex_Ent_Bacterial_Genus_Name;")
                )
        );

        model.apply(snapshot(PlanetBody.listed(profile)));

        assertEquals(BodyKnowledgeLevel.MAPPED, model.getValueAt(0, 2));
        assertEquals("B:3", model.getValueAt(0, 4));
        assertEquals("1 of 3 collected", model.getValueAt(0, 5));
    }

    /**
     * A count without names is not a denominator.
     *
     * <p>Before the surface survey the game states how many biological signals
     * there are and never which. "0 of 3 collected" would claim three known
     * organisms nobody has collected, which is a claim no record makes.</p>
     */
    @Test
    void aCountedSignalWithNoSurveyIsNotADenominator() {
        SystemRegistryTableModel model = new SystemRegistryTableModel();

        model.apply(snapshot(PlanetBody.listed(new BodyProfile(
                new BodyIdentity(SYSTEM, 5),
                null,
                List.of(),
                null,
                null,
                null,
                null,
                BodyKnowledgeLevel.SCANNED,
                KnowledgeSource.OBSERVED,
                Map.of("BIOLOGICAL", 3),
                BiologicalSurvey.EMPTY
        ))));

        assertEquals("B:3", model.getValueAt(0, 4));
        assertEquals("", model.getValueAt(0, 5));
    }

    @Test
    void theTabAppliesASnapshotOnTheEventDispatchThread() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SystemRegistryTab tab = new SystemRegistryTab();
            tab.apply(snapshot(starAt(1, 0), planetAt(4, 1, 0)));
            tab.apply(SystemRegistrySnapshot.empty(7));
            tab.apply(SystemRegistrySnapshot.unavailable(8));
        });
    }

    // ------------------------------------------------------------- fixtures

    private static List<Object> column(
            SystemRegistryTableModel model,
            int column
    ) {
        return java.util.stream.IntStream.range(0, model.getRowCount())
                .mapToObj(row -> model.getValueAt(row, column))
                .toList();
    }

    private static SystemRegistrySnapshot snapshot(SystemObject... objects) {
        Map<Long, SystemObject> byId = new TreeMap<>();
        for (SystemObject object : objects) {
            byId.put(object.bodyId(), object);
        }
        return new SystemRegistrySnapshot(
                1,
                true,
                SYSTEM,
                "Survey Alpha",
                null,
                null,
                false,
                byId
        );
    }

    private static SystemObject barycentreAt(long bodyId) {
        return new kairon.system.Barycentre(listed(bodyId));
    }

    private static SystemObject starAt(long bodyId, long... chain) {
        return StarBody.listed(listed(bodyId, ParentKind.BARYCENTRE, chain));
    }

    private static SystemObject planetAt(long bodyId, long... chain) {
        return PlanetBody.listed(listed(bodyId, ParentKind.STAR, chain));
    }

    private static SystemObject moonAt(long bodyId, long... chain) {
        return PlanetBody.listed(new BodyProfile(
                new BodyIdentity(SYSTEM, bodyId),
                "Survey Alpha A 2 a",
                parents(ParentKind.PLANET, chain),
                null,
                null,
                null,
                null,
                BodyKnowledgeLevel.SCANNED,
                KnowledgeSource.OBSERVED,
                Map.of(),
                BiologicalSurvey.EMPTY
        ));
    }

    private static SystemObject signalsOnlyAt(long bodyId) {
        return new kairon.system.UnclassifiedBody(listed(bodyId));
    }

    private static BodyProfile listed(long bodyId) {
        return BodyProfile.listed(
                new BodyIdentity(SYSTEM, bodyId),
                List.of()
        );
    }

    private static BodyProfile listed(
            long bodyId,
            ParentKind immediate,
            long... chain
    ) {
        return BodyProfile.listed(
                new BodyIdentity(SYSTEM, bodyId),
                parents(immediate, chain)
        );
    }

    /**
     * A chain whose first link carries the stated kind and whose remainder is
     * spelled out only as far as the test needs it.
     */
    private static List<BodyParent> parents(
            ParentKind immediate,
            long... chain
    ) {
        List<BodyParent> links = new java.util.ArrayList<>(chain.length);
        for (int index = 0; index < chain.length; index++) {
            links.add(new BodyParent(
                    index == 0 ? immediate : ParentKind.BARYCENTRE,
                    chain[index]
            ));
        }
        return List.copyOf(links);
    }
}
