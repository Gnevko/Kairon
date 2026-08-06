package kairon.ui;

import kairon.projection.ProjectedObservation;
import kairon.projection.ProjectedObservationBus;
import kairon.system.SystemRegistrySnapshot;

import java.util.Objects;

/**
 * Projection-to-GUI bridge for the current-system registry.
 *
 * <p>Every projection carries the registry as it then stood, so there is
 * nothing to query and nothing to poll: this forwards what it is handed. It
 * subscribes to the projected bus rather than the observation bus because the
 * snapshot is made at the projection boundary and does not exist before it.</p>
 *
 * <p>It forwards only what changed. Most observations tell the registry nothing
 * — a docking request says nothing about a moon — and the hub's update queue is
 * bounded and shared with the observation rows, so a refresh per publication
 * would push rows out of the table to redraw a system that did not move. The
 * memory of what was last sent is this subscriber's own, which is the rule for
 * everything a subscriber derives.</p>
 */
public final class DesktopSystemRegistrySubscriber {

    public static final String SUBSCRIBER_ID = "desktop-ui-system-registry";

    private final KaironGuiHub guiHub;

    private SystemRegistrySnapshot lastPosted;

    public DesktopSystemRegistrySubscriber(KaironGuiHub guiHub) {
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
        SystemRegistrySnapshot snapshot = projected.systemRegistry();
        if (snapshot.sameContentAs(lastPosted)) {
            return;
        }
        lastPosted = snapshot;
        guiHub.postSystemRegistry(snapshot);
    }
}
