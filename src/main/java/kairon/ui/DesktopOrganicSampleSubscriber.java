package kairon.ui;

import kairon.observation.journal.event.exploration.ScanOrganic;
import kairon.projection.ProjectedObservation;
import kairon.projection.ProjectedObservationBus;

import java.util.Objects;

/**
 * Projection-to-GUI bridge for the sample that was just collected.
 *
 * <p>One event and one field. {@code ScanOrganic.Analysed} is the step that
 * finishes a sampling sequence — the parser decided that once, and this asks
 * the type rather than re-reading {@code ScanType} — and the species symbol is
 * what says which organism was collected. What it is called and what it pays
 * are the registry's to answer, and the tab already has the registry.</p>
 *
 * <p>Every other step is ignored, including the log that starts a sequence: a
 * row marked as collected while the Commander is still walking between samples
 * would be the table saying something the game has not said yet.</p>
 */
public final class DesktopOrganicSampleSubscriber {

    public static final String SUBSCRIBER_ID = "desktop-ui-organic-sample";

    private final KaironGuiHub guiHub;

    public DesktopOrganicSampleSubscriber(KaironGuiHub guiHub) {
        this.guiHub = Objects.requireNonNull(guiHub, "guiHub");
    }

    public ProjectedObservationBus.Subscription subscribeTo(
            ProjectedObservationBus bus
    ) {
        Objects.requireNonNull(bus, "bus");
        return bus.subscribe(SUBSCRIBER_ID, this::onProjectedObservation);
    }

    private void onProjectedObservation(ProjectedObservation projected) {
        Objects.requireNonNull(projected, "projected");
        if (!(projected.trigger().payload()
                instanceof ScanOrganic.Analysed analysed)) {
            return;
        }
        String species = species(analysed);
        if (species != null) {
            guiHub.postOrganicSample(species);
        }
    }

    private static String species(ScanOrganic.Analysed analysed) {
        var species = analysed.raw().parsedJsonObject().get("Species");
        return species != null && species.isTextual() && !species.textValue().isBlank()
                ? species.textValue()
                : null;
    }
}
