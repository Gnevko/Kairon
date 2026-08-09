package kairon.app;

import kairon.behavior.bus.BehaviorGraphObservationProcessor;
import kairon.behavior.event.BehaviorGraphEventSource;
import kairon.behavior.graph.BehaviorGraphQueryService;
import kairon.behavior.graph.BehaviorGraphService;
import kairon.behavior.persistence.BehaviorGraphStore;
import kairon.behavior.persistence.JsonBehaviorGraphStore;
import kairon.config.KaironConfiguration;
import kairon.config.KaironConfiguration.ResolvedProviderConfiguration;
import kairon.diagnostics.TelemetryDiagnosticSubscriber;
import kairon.llm.LlmClient;
import kairon.llm.LlmRequestStatistics;
import kairon.llm.OpenAiCompatibleLlmClient;
import kairon.llm.DecisionPromptFactory;
import kairon.observation.ObservationDraft.ObservationSource;
import kairon.observation.bus.InProcessObservationBus;
import kairon.observation.bus.ObservationBus.ObservationSubscription;
import kairon.observation.journal.JournalLineParser;
import kairon.observation.journal.JournalObservationAdapter;
import kairon.observation.journal.JournalReplaySource;
import kairon.observation.journal.PollingJournalTailReader;
import kairon.observation.status.PollingStatusWatcher;
import kairon.observation.status.StatusObservationAdapter;
import kairon.observation.status.StatusSnapshotParser;
import kairon.observer.LlmJournalObserverSubscriber;
import kairon.observer.ObserverTurnCoordinator;
import kairon.observer.decision.DecisionTurnPolicy;
import kairon.observer.decision.JacksonDecisionRequestSerializer;
import kairon.observer.decision.LlmDecisionRequestCompactor;
import kairon.bio.JsonOrganicRegistryLoader;
import kairon.bio.OrganicRegistry;
import kairon.observer.decision.DecisionOrganicNames;
import kairon.observer.decision.LlmDecisionRequestFactory;
import kairon.output.CommentSink;
import kairon.output.CommentSink.SpeechDescriptor;
import kairon.output.ConsoleCommentSink;
import kairon.output.SpeechGateway;
import kairon.speech.GoogleCloudTextToSpeechClient;
import kairon.speech.JavaSoundAudioPlayer;
import kairon.state.CurrentGameStateProjector;
import kairon.state.CurrentGameStateView;
import kairon.projection.ObservationProjectionCoordinator;
import kairon.projection.ObservationProjectionSubscriber;
import kairon.projection.ProjectedObservationBus;
import kairon.trace.JsonLinesTurnTraceWriter;
import kairon.ui.DesktopObserverTurnListener;
import kairon.ui.DesktopOrganicSampleSubscriber;
import kairon.ui.DesktopSystemRegistrySubscriber;
import kairon.ui.DesktopUiSubscriber;
import kairon.ui.KaironGuiHub;
import kairon.ui.swing.SwingKaironGuiHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Application composition root for raw sources, sequential projection, and
 * downstream observer processing.
 */
public final class KaironApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(KaironApplication.class);

    public static void main(String[] args) {
        int exitCode = new KaironApplication().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public int run(String[] args) {
        try {
            KaironConfiguration configuration = KaironConfiguration.load(args);
            ResolvedProviderConfiguration provider = configuration.resolveActiveProvider();
            return switch (configuration.source().mode()) {
                case REPLAY -> runReplay(configuration, provider);
                case LIVE -> runLive(configuration, provider);
            };
        } catch (KaironConfiguration.ConfigurationException failure) {
            reportConfigurationFailure(failure);
            return 2;
        } catch (GoogleCloudTextToSpeechClient
                .SpeechClientInitializationException failure) {
            reportSpeechStartupFailure(failure);
            return 1;
        } catch (SwingKaironGuiHub.GuiInitializationException failure) {
            reportFailure("GUI_START_FAILED", failure);
            return 1;
        } catch (CompletionException failure) {
            reportFailure("KAIRON_RUNTIME_FAILED", unwrap(failure));
            return 1;
        } catch (RuntimeException failure) {
            reportFailure("KAIRON_RUNTIME_FAILED", failure);
            return 1;
        }
    }

    private int runReplay(
            KaironConfiguration configuration,
            ResolvedProviderConfiguration provider
    ) {
        try (RuntimeWiring wiring = RuntimeWiring.create(configuration, provider)) {
            JournalObservationAdapter adapter = journalAdapter("replay");
            try (JournalReplaySource source = new JournalReplaySource(
                    configuration.source().replayFile(),
                    new JournalLineParser(),
                    adapter,
                    wiring.bus
            )) {
                CompletableFuture<Void> guiCloseRequested =
                        wiring.guiHub.closeRequested().toCompletableFuture();
                CompletableFuture<JournalReplaySource.ReplayReport> replay =
                        source.publishAll().toCompletableFuture();
                if (wiring.guiHub.enabled()) {
                    guiCloseRequested.thenRun(source::requestStop);
                }
                JournalReplaySource.ReplayReport report = replay.join();
                wiring.awaitProjectionIdle()
                        .toCompletableFuture()
                        .join();
                if (report.cancelled()
                        && wiring.guiHub.enabled()
                        && guiCloseRequested.isDone()) {
                    LOGGER.info(
                            "REPLAY_SOURCE_CANCELLED completeRecords={} "
                                    + "publishedRecords={}",
                            report.completeRecordCount(),
                            report.publishedRecordCount()
                    );
                    return 0;
                }
                boolean requiredHandoffFailed =
                        report.handlerFailures().stream().anyMatch(failure ->
                                wiring.projectionOwnsSubscriberId(
                                        failure.subscriberId()
                                )
                        );
                if (!report.successful() || requiredHandoffFailed) {
                    LOGGER.error(
                            "REPLAY_SOURCE_FAILED completeRecords={} publishedRecords={} "
                                    + "exhaustionSignalAccepted={} failedObservationId={} "
                                    + "requiredHandoffFailed={}",
                            report.completeRecordCount(),
                            report.publishedRecordCount(),
                            report.exhaustionSignalAccepted(),
                            report.failedObservationId().orElse("<none>"),
                            requiredHandoffFailed
                    );
                    return 1;
                }
                CompletableFuture<Void> observerIdle =
                        wiring.coordinator.awaitIdle().toCompletableFuture();
                if (wiring.guiHub.enabled()) {
                    CompletableFuture.anyOf(observerIdle, guiCloseRequested)
                            .join();
                    if (guiCloseRequested.isDone()
                            && !observerIdle.isDone()) {
                        LOGGER.info(
                                "REPLAY_OBSERVER_CANCELLED_ON_GUI_CLOSE"
                        );
                        return 0;
                    }
                }
                observerIdle.join();
                LOGGER.info(
                        "REPLAY_SOURCE_COMPLETED completeRecords={} publishedRecords={}",
                        report.completeRecordCount(),
                        report.publishedRecordCount()
                );
                if (wiring.guiHub.enabled()) {
                    LOGGER.info("REPLAY_GUI_WAITING_FOR_CLOSE");
                    wiring.guiHub.closeRequested()
                            .toCompletableFuture()
                            .join();
                }
                return 0;
            }
        }
    }

    private int runLive(
            KaironConfiguration configuration,
            ResolvedProviderConfiguration provider
    ) {
        RuntimeWiring wiring = RuntimeWiring.create(configuration, provider);
        PollingJournalTailReader journalSource = null;
        PollingStatusWatcher statusSource = null;
        try {
            statusSource = new PollingStatusWatcher(
                    configuration.source()
                            .journalDirectory()
                            .resolve("Status.json"),
                    new StatusSnapshotParser(),
                    statusAdapter(),
                    wiring.bus
            );
            journalSource = new PollingJournalTailReader(
                    configuration.source().journalDirectory(),
                    new JournalLineParser(),
                    journalAdapter("live"),
                    wiring.bus
            );
        } catch (RuntimeException failure) {
            if (journalSource != null) {
                try {
                    journalSource.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (statusSource != null) {
                try {
                    statusSource.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            wiring.close();
            throw failure;
        }
        final PollingJournalTailReader liveJournalSource = journalSource;
        final PollingStatusWatcher liveStatusSource = statusSource;
        LiveRuntime runtime = new LiveRuntime(
                liveJournalSource,
                liveStatusSource,
                wiring
        );
        try {
            PollingJournalTailReader.BootstrapPublicationReport bootstrap =
                    liveJournalSource.publishBootstrap()
                            .toCompletableFuture()
                            .join();
            wiring.awaitProjectionIdle().toCompletableFuture().join();
            wiring.coordinator.awaitApplied().toCompletableFuture().join();
            ObserverTurnCoordinator.ObserverSnapshot snapshot =
                    wiring.coordinator.snapshot().toCompletableFuture().join();
            boolean observerHandoffFailed =
                    bootstrap.handlerFailures().stream().anyMatch(failure ->
                            wiring.projectionOwnsSubscriberId(
                                    failure.subscriberId()
                            )
                    );
            if (!bootstrap.successful()
                    || observerHandoffFailed
                    || snapshot.queuedNewCount() != 0
                    || snapshot.completedTurnCount() != 0) {
                throw new IllegalStateException("BOOTSTRAP_INVARIANT_FAILED");
            }

            PollingStatusWatcher.BootstrapPublicationReport statusBootstrap =
                    liveStatusSource.publishBootstrap()
                            .toCompletableFuture()
                            .join();
            wiring.awaitProjectionIdle().toCompletableFuture().join();
            boolean statusHandoffFailed =
                    statusBootstrap.handlerFailures().stream()
                            .anyMatch(failure ->
                                    wiring.projectionOwnsSubscriberId(
                                            failure.subscriberId()
                                    )
                            );
            if (!statusBootstrap.successful() || statusHandoffFailed) {
                throw new IllegalStateException(
                        "STATUS_BOOTSTRAP_INVARIANT_FAILED"
                );
            }

            liveJournalSource.terminalFailure().thenAccept(failure ->
                    runtime.signalSourceFailure(
                            "JOURNAL_SOURCE_FAILED",
                            failure
                    )
            );
            liveStatusSource.terminalFailure().thenAccept(failure ->
                    runtime.signalSourceFailure(
                            "STATUS_SOURCE_FAILED",
                            failure
                    )
            );
            liveStatusSource.startWatching();
            liveJournalSource.startFollowing();
            Thread shutdownHook = new Thread(runtime::close, "kairon-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            if (wiring.guiHub.enabled()) {
                wiring.guiHub.closeRequested().thenRun(() -> {
                    Thread closeThread = new Thread(
                            runtime::close,
                            "kairon-gui-shutdown"
                    );
                    closeThread.setDaemon(false);
                    closeThread.start();
                });
            }
            LOGGER.info(
                    "LIVE_OBSERVATION_STARTED bootstrapRecords={} "
                            + "statusBootstrapSnapshots={} journalDirectory={} "
                            + "statusFile={}",
                    bootstrap.publishedRecordCount(),
                    statusBootstrap.publishedSnapshotCount(),
                    configuration.source().journalDirectory(),
                    configuration.source()
                            .journalDirectory()
                            .resolve("Status.json")
            );
            try {
                runtime.awaitShutdown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                runtime.close();
                removeShutdownHook(shutdownHook);
                return 130;
            }
            if (runtime.sourceFailure() != null) {
                SourceFailure sourceFailure = runtime.sourceFailure();
                reportFailure(
                        sourceFailure.code(),
                        sourceFailure.failure()
                );
                runtime.close();
                removeShutdownHook(shutdownHook);
                return 1;
            }
            removeShutdownHook(shutdownHook);
            return 0;
        } catch (RuntimeException failure) {
            runtime.close();
            throw failure;
        }
    }

    private static JournalObservationAdapter journalAdapter(String mode) {
        return new JournalObservationAdapter(new ObservationSource(
                "elite-dangerous-journal",
                mode + "-" + UUID.randomUUID()
        ));
    }

    private static StatusObservationAdapter statusAdapter() {
        return new StatusObservationAdapter(
                new ObservationSource(
                        "elite-dangerous-status",
                        "live-" + UUID.randomUUID()
                ),
                "Status.json"
        );
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress.
        }
    }

    private static void reportFailure(String code, Throwable failure) {
        String safeMessage = code + " cause=" + failure.getClass().getSimpleName();
        LOGGER.error(safeMessage);
        System.err.println(safeMessage);
    }

    private static void reportConfigurationFailure(
            KaironConfiguration.ConfigurationException failure
    ) {
        String safeMessage = failure.code() + " path=" + failure.path();
        LOGGER.error(safeMessage);
        System.err.println(safeMessage);
    }

    private static void reportSpeechStartupFailure(
            GoogleCloudTextToSpeechClient
                    .SpeechClientInitializationException failure
    ) {
        String safeMessage = "SPEECH_CLIENT_START_FAILED category="
                + failure.category()
                + " cause="
                + failure.getClass().getSimpleName();
        LOGGER.error(safeMessage);
        System.err.println(safeMessage);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class RuntimeWiring implements AutoCloseable {

        private final InProcessObservationBus bus;
        private final LlmClient llmClient;
        private final JsonLinesTurnTraceWriter traceWriter;
        private final ObserverTurnCoordinator coordinator;
        private final ObservationProjectionCoordinator projectionCoordinator;
        private final ObservationProjectionSubscriber.Subscription
                projectionSubscription;
        private final LlmJournalObserverSubscriber.Subscriptions llmSubscriptions;
        private final ObservationSubscription diagnosticSubscription;
        private final BehaviorGraphStore behaviorGraphStore;
        private final CurrentGameStateView currentGameState;
        private final KaironGuiHub guiHub;
        private final ObservationSubscription guiSubscription;
        private final ProjectedObservationBus.Subscription
                guiRegistrySubscription;
        private final ProjectedObservationBus.Subscription
                guiOrganicSubscription;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RuntimeWiring(
                InProcessObservationBus bus,
                LlmClient llmClient,
                JsonLinesTurnTraceWriter traceWriter,
                ObserverTurnCoordinator coordinator,
                ObservationProjectionCoordinator projectionCoordinator,
                ObservationProjectionSubscriber.Subscription
                        projectionSubscription,
                LlmJournalObserverSubscriber.Subscriptions llmSubscriptions,
                ObservationSubscription diagnosticSubscription,
                BehaviorGraphStore behaviorGraphStore,
                CurrentGameStateView currentGameState,
                KaironGuiHub guiHub,
                ObservationSubscription guiSubscription,
                ProjectedObservationBus.Subscription guiRegistrySubscription,
                ProjectedObservationBus.Subscription guiOrganicSubscription
        ) {
            this.bus = bus;
            this.llmClient = llmClient;
            this.traceWriter = traceWriter;
            this.coordinator = coordinator;
            this.projectionCoordinator = projectionCoordinator;
            this.projectionSubscription = projectionSubscription;
            this.llmSubscriptions = llmSubscriptions;
            this.diagnosticSubscription = diagnosticSubscription;
            this.behaviorGraphStore = behaviorGraphStore;
            this.currentGameState = Objects.requireNonNull(
                    currentGameState,
                    "currentGameState"
            );
            this.guiHub = guiHub;
            this.guiSubscription = guiSubscription;
            this.guiRegistrySubscription = guiRegistrySubscription;
            this.guiOrganicSubscription = guiOrganicSubscription;
        }

        private static RuntimeWiring create(
                KaironConfiguration configuration,
                ResolvedProviderConfiguration provider
        ) {
            InProcessObservationBus bus = null;
            OpenAiCompatibleLlmClient transport = null;
            LlmRequestStatistics statistics = null;
            LlmClient llmClient = null;
            JsonLinesTurnTraceWriter traceWriter = null;
            ObserverTurnCoordinator coordinator = null;
            CommentSink commentSink = null;
            ProjectedObservationBus projectedObservationBus = null;
            ObservationProjectionCoordinator projectionCoordinator = null;
            ObservationProjectionSubscriber.Subscription
                    projectionSubscription = null;
            LlmJournalObserverSubscriber.Subscriptions llmSubscriptions = null;
            ObservationSubscription diagnosticSubscription = null;
            BehaviorGraphStore behaviorGraphStore = null;
            BehaviorGraphObservationProcessor graphProcessor = null;
            BehaviorGraphQueryService behaviorGraphQueryService = null;
            BehaviorGraphEventSource behaviorGraphEventSource = null;
            CurrentGameStateProjector currentGameState = null;
            KaironGuiHub guiHub = KaironGuiHub.disabled();
            ObservationSubscription guiSubscription = null;
            ProjectedObservationBus.Subscription guiRegistrySubscription =
                    null;
            ProjectedObservationBus.Subscription guiOrganicSubscription = null;
            OrganicRegistry organicRegistry = OrganicRegistry.EMPTY;
            try {
                bus = new InProcessObservationBus();
                if (configuration.behaviorGraph().enabled()) {
                    behaviorGraphStore = new JsonBehaviorGraphStore(
                            configuration.behaviorGraph().storageDirectory()
                    );
                    BehaviorGraphService behaviorGraphService =
                            new BehaviorGraphService(
                                    configuration.behaviorGraph(),
                                    behaviorGraphStore
                            );
                    behaviorGraphQueryService =
                            new BehaviorGraphQueryService(
                                    behaviorGraphService
                            );
                    behaviorGraphEventSource =
                            behaviorGraphService.eventSource();
                    graphProcessor =
                            new BehaviorGraphObservationProcessor(
                                    behaviorGraphService
                            );
                }
                /*
                 * The run starts from the ship the graph was last active on.
                 * Read here rather than by the projector itself: canonical
                 * state is a projection of the journal and reads no store, and
                 * a session opening in an SRV has no journal record naming a
                 * ship at all. With the graph disabled there is no store and
                 * therefore no memory, which is the same run as before this
                 * existed — no ship until the first Loadout.
                 */
                currentGameState = new CurrentGameStateProjector(
                        behaviorGraphStore == null
                                ? null
                                : behaviorGraphStore.lastKnownShip()
                                        .orElse(null)
                );
                projectedObservationBus = new ProjectedObservationBus();
                projectionCoordinator =
                        new ObservationProjectionCoordinator(
                                currentGameState,
                                java.util.Optional.ofNullable(graphProcessor),
                                java.util.Optional.ofNullable(
                                        behaviorGraphQueryService
                                ),
                                projectedObservationBus
                        );
                projectionSubscription =
                        new ObservationProjectionSubscriber(
                                projectionCoordinator
                        ).subscribeTo(bus);
                guiHub = createGuiHub(
                        configuration,
                        behaviorGraphQueryService,
                        behaviorGraphEventSource
                );
                guiHub.start();
                transport = new OpenAiCompatibleLlmClient(provider);
                statistics = new LlmRequestStatistics(provider.pricing());
                llmClient = statistics.instrument(transport);
                traceWriter = new JsonLinesTurnTraceWriter(
                        configuration.observer().traceFile()
                );
                traceWriter.probe();
                commentSink = createCommentSink(configuration);
                // kairon-llm-situation-v2.1 is the only production context.
                // There is no fallback and no version selector: a turn either
                // produces one sparse request or, on overflow, none at all.
                organicRegistry = organicRegistry(configuration);
                coordinator = new ObserverTurnCoordinator(
                        configuration.observer(),
                        new LlmDecisionRequestCompactor(
                                new LlmDecisionRequestFactory(
                                        new DecisionOrganicNames(
                                                organicRegistry,
                                                configuration.observer()
                                                        .outputLanguage()
                                        )
                                ),
                                new JacksonDecisionRequestSerializer(),
                                DecisionTurnPolicy.production()
                        ),
                        new DecisionPromptFactory(),
                        llmClient,
                        commentSink,
                        traceWriter,
                        new DesktopObserverTurnListener(guiHub)
                );
                LlmJournalObserverSubscriber llmSubscriber =
                        new LlmJournalObserverSubscriber(coordinator);
                llmSubscriptions = llmSubscriber.subscribeTo(
                        projectedObservationBus
                );
                diagnosticSubscription =
                        new TelemetryDiagnosticSubscriber().subscribeTo(bus);
                if (guiHub.enabled()) {
                    guiSubscription =
                            new DesktopUiSubscriber(guiHub).subscribeTo(bus);
                    guiRegistrySubscription =
                            new DesktopSystemRegistrySubscriber(guiHub)
                                    .subscribeTo(projectedObservationBus);
                    guiOrganicSubscription =
                            new DesktopOrganicSampleSubscriber(guiHub)
                                    .subscribeTo(projectedObservationBus);
                    // The price table is read from a file once and never
                    // changes, so it is posted once rather than per
                    // observation.
                    guiHub.postOrganicRegistry(organicRegistry.priced(
                            configuration.observer().outputLanguage()
                    ));
                }
                boolean guiSubscriptionActive = !guiHub.enabled()
                        || guiSubscription != null
                        && guiSubscription.isActive()
                        && guiRegistrySubscription != null
                        && guiRegistrySubscription.isActive()
                        && guiOrganicSubscription != null
                        && guiOrganicSubscription.isActive();
                if (!llmSubscriptions.allActive()
                        || !projectionSubscription.isActive()
                        || !diagnosticSubscription.isActive()
                        || !guiSubscriptionActive) {
                    throw new IllegalStateException(
                            "REQUIRED_OBSERVATION_SUBSCRIPTIONS_NOT_ACTIVE"
                    );
                }
                return new RuntimeWiring(
                        bus,
                        llmClient,
                        traceWriter,
                        coordinator,
                        projectionCoordinator,
                        projectionSubscription,
                        llmSubscriptions,
                        diagnosticSubscription,
                        behaviorGraphStore,
                        currentGameState,
                        guiHub,
                        guiSubscription,
                        guiRegistrySubscription,
                        guiOrganicSubscription
                );
            } catch (RuntimeException failure) {
                RuntimeException cleanupFailure = null;
                if (guiOrganicSubscription != null) {
                    ProjectedObservationBus.Subscription createdOrganic =
                            guiOrganicSubscription;
                    cleanupFailure = attempt(
                            cleanupFailure,
                            createdOrganic::close
                    );
                }
                if (guiRegistrySubscription != null) {
                    ProjectedObservationBus.Subscription createdRegistry =
                            guiRegistrySubscription;
                    cleanupFailure = attempt(
                            cleanupFailure,
                            createdRegistry::close
                    );
                }
                if (guiSubscription != null) {
                    ObservationSubscription createdGuiSubscription =
                            guiSubscription;
                    cleanupFailure = attempt(
                            cleanupFailure,
                            createdGuiSubscription::close
                    );
                }
                KaironGuiHub createdGuiHub = guiHub;
                cleanupFailure = attempt(
                        cleanupFailure,
                        createdGuiHub::close
                );
                if (diagnosticSubscription != null) {
                    cleanupFailure = attempt(
                            cleanupFailure,
                            diagnosticSubscription::close
                    );
                }
                if (llmSubscriptions != null) {
                    cleanupFailure = attempt(cleanupFailure, llmSubscriptions::close);
                }
                if (projectionSubscription != null) {
                    ObservationProjectionSubscriber.Subscription
                            createdProjectionSubscription =
                            projectionSubscription;
                    cleanupFailure = attempt(
                            cleanupFailure,
                            createdProjectionSubscription::close
                    );
                }
                if (projectionCoordinator != null) {
                    ObservationProjectionCoordinator
                            createdProjectionCoordinator =
                            projectionCoordinator;
                    cleanupFailure = attempt(
                            cleanupFailure,
                            createdProjectionCoordinator::close
                    );
                } else {
                    if (graphProcessor != null) {
                        BehaviorGraphObservationProcessor
                                createdGraphProcessor = graphProcessor;
                        cleanupFailure = attempt(
                                cleanupFailure,
                                createdGraphProcessor::close
                        );
                    }
                    if (projectedObservationBus != null) {
                        ProjectedObservationBus createdProjectedBus =
                                projectedObservationBus;
                        cleanupFailure = attempt(
                                cleanupFailure,
                                createdProjectedBus::close
                        );
                    }
                }
                if (coordinator != null) {
                    ObserverTurnCoordinator createdCoordinator = coordinator;
                    cleanupFailure = attempt(cleanupFailure, createdCoordinator::close);
                }
                if (commentSink != null) {
                    CommentSink createdCommentSink = commentSink;
                    cleanupFailure = attempt(
                            cleanupFailure,
                            createdCommentSink::close
                    );
                }
                if (bus != null) {
                    InProcessObservationBus createdBus = bus;
                    cleanupFailure = attempt(
                            cleanupFailure,
                            createdBus::close
                    );
                }
                if (behaviorGraphStore != null) {
                    BehaviorGraphStore createdBehaviorGraphStore =
                            behaviorGraphStore;
                    cleanupFailure = attempt(
                            cleanupFailure,
                            createdBehaviorGraphStore::close
                    );
                }
                if (llmClient != null) {
                    LlmClient createdLlmClient = llmClient;
                    cleanupFailure = attempt(
                            cleanupFailure,
                            createdLlmClient::close
                    );
                } else {
                    if (statistics != null) {
                        LlmRequestStatistics createdStatistics = statistics;
                        cleanupFailure = attempt(
                                cleanupFailure,
                                createdStatistics::close
                        );
                    }
                    if (transport != null) {
                        OpenAiCompatibleLlmClient createdTransport = transport;
                        cleanupFailure = attempt(
                                cleanupFailure,
                                createdTransport::close
                        );
                    }
                }
                if (traceWriter != null) {
                    JsonLinesTurnTraceWriter createdTraceWriter = traceWriter;
                    cleanupFailure = attempt(
                            cleanupFailure,
                            createdTraceWriter::close
                    );
                }
                if (cleanupFailure != null) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private static KaironGuiHub createGuiHub(
                KaironConfiguration configuration,
                BehaviorGraphQueryService behaviorGraphQueryService,
                BehaviorGraphEventSource behaviorGraphEventSource
        ) {
            if (!configuration.ui().enabled()) {
                return KaironGuiHub.disabled();
            }
            if (behaviorGraphQueryService == null
                    || behaviorGraphEventSource == null) {
                return new SwingKaironGuiHub(configuration.ui());
            }
            return new SwingKaironGuiHub(
                    configuration.ui(),
                    behaviorGraphQueryService,
                    behaviorGraphEventSource
            );
        }

        /**
         * How organisms are named this run.
         *
         * <p>No configured file means no registry, which is a supported way to
         * run and not a degraded one: every organism is then named by the word
         * the journal itself carried. A configured file that will not load is
         * a startup failure — the loader says exactly what was wrong with it,
         * and a registry half-read would name half the organisms and silently
         * fall back on the rest (ADR-0028).</p>
         */
        private static OrganicRegistry organicRegistry(
                KaironConfiguration configuration
        ) {
            Path registryFile = configuration.bio().registryFile();
            if (registryFile == null) {
                LOGGER.info("ORGANIC_REGISTRY_ABSENT");
                return OrganicRegistry.EMPTY;
            }
            OrganicRegistry registry = JsonOrganicRegistryLoader.load(registryFile);
            LOGGER.info(
                    "ORGANIC_REGISTRY_LOADED file={} organisms={} language={}",
                    registryFile,
                    registry.size(),
                    configuration.observer().outputLanguage()
            );
            return registry;
        }

        private static CommentSink createCommentSink(
                KaironConfiguration configuration
        ) {
            KaironConfiguration.SpeechConfiguration speech =
                    configuration.speech();
            SpeechDescriptor descriptor = new SpeechDescriptor(
                    speech.enabled(),
                    speech.provider().name(),
                    speech.voiceName()
            );
            ConsoleCommentSink console = new ConsoleCommentSink(
                    System.out,
                    descriptor
            );
            if (!speech.enabled()) {
                return console;
            }
            GoogleCloudTextToSpeechClient synthesisClient =
                    new GoogleCloudTextToSpeechClient(
                            speech,
                            configuration.googleCloudTextToSpeechApiKey()
                    );
            JavaSoundAudioPlayer audioPlayer =
                    new JavaSoundAudioPlayer();
            try {
                return new SpeechGateway(
                        speech,
                        synthesisClient,
                        audioPlayer,
                        console
                );
            } catch (RuntimeException failure) {
                audioPlayer.close();
                synthesisClient.close();
                throw failure;
            }
        }

        private CompletionStage<Void> awaitProjectionIdle() {
            return projectionCoordinator.awaitIdle();
        }

        private boolean projectionOwnsSubscriberId(String subscriberId) {
            return projectionSubscription.ownsSubscriberId(subscriberId);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            RuntimeException firstFailure = null;
            firstFailure = attempt(firstFailure,
                    () -> bus.drainAndClose().toCompletableFuture().join());
            firstFailure = attempt(
                    firstFailure,
                    () -> projectionCoordinator
                            .shutdown()
                            .toCompletableFuture()
                            .join()
            );
            firstFailure = attempt(firstFailure,
                    () -> coordinator.shutdown().toCompletableFuture().join());
            if (guiOrganicSubscription != null) {
                firstFailure = attempt(
                        firstFailure,
                        guiOrganicSubscription::close
                );
            }
            if (guiRegistrySubscription != null) {
                firstFailure = attempt(
                        firstFailure,
                        guiRegistrySubscription::close
                );
            }
            if (guiSubscription != null) {
                firstFailure = attempt(
                        firstFailure,
                        guiSubscription::close
                );
            }
            firstFailure = attempt(firstFailure, diagnosticSubscription::close);
            firstFailure = attempt(firstFailure, llmSubscriptions::close);
            firstFailure = attempt(
                    firstFailure,
                    projectionSubscription::close
            );
            firstFailure = attempt(firstFailure, guiHub::close);
            if (behaviorGraphStore != null) {
                firstFailure = attempt(
                        firstFailure,
                        behaviorGraphStore::close
                );
            }
            firstFailure = attempt(firstFailure, llmClient::close);
            firstFailure = attempt(firstFailure, traceWriter::close);
            if (firstFailure != null) {
                throw firstFailure;
            }
        }

        private static RuntimeException attempt(
                RuntimeException firstFailure,
                Runnable action
        ) {
            try {
                action.run();
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    return failure;
                }
                firstFailure.addSuppressed(failure);
            }
            return firstFailure;
        }
    }

    private static final class LiveRuntime implements AutoCloseable {

        private final PollingJournalTailReader journalSource;
        private final PollingStatusWatcher statusSource;
        private final RuntimeWiring wiring;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<SourceFailure> sourceFailure =
                new AtomicReference<>();

        private LiveRuntime(
                PollingJournalTailReader journalSource,
                PollingStatusWatcher statusSource,
                RuntimeWiring wiring
        ) {
            this.journalSource = Objects.requireNonNull(
                    journalSource,
                    "journalSource"
            );
            this.statusSource = Objects.requireNonNull(
                    statusSource,
                    "statusSource"
            );
            this.wiring = wiring;
        }

        private void awaitShutdown() throws InterruptedException {
            stopped.await();
        }

        private void signalSourceFailure(String code, Throwable failure) {
            if (sourceFailure.compareAndSet(
                    null,
                    new SourceFailure(code, failure)
            )) {
                stopped.countDown();
            }
        }

        private SourceFailure sourceFailure() {
            return sourceFailure.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            CompletionStage<PollingStatusWatcher.StatusStopReport> statusStop =
                    statusSource.stopAndDrain();
            CompletionStage<PollingJournalTailReader.JournalStopReport>
                    journalStop = journalSource.stopAndDrain();
            try {
                PollingJournalTailReader.JournalStopReport report =
                        journalStop.toCompletableFuture().join();
                if (!report.successful()) {
                    LOGGER.error(
                            "FINAL_SOURCE_DRAIN_FAILED uncommittedPositions={}",
                            report.uncommittedPositions().size()
                    );
                }
            } catch (RuntimeException failure) {
                reportFailure("FINAL_SOURCE_DRAIN_FAILED", unwrap(failure));
            }
            try {
                PollingStatusWatcher.StatusStopReport report =
                        statusStop.toCompletableFuture().join();
                if (!report.successful()) {
                    LOGGER.error(
                            "FINAL_STATUS_SOURCE_DRAIN_FAILED "
                                    + "handlerFailures={} failurePresent={}",
                            report.handlerFailures().size(),
                            report.failure().isPresent()
                    );
                }
            } catch (RuntimeException failure) {
                reportFailure(
                        "FINAL_STATUS_SOURCE_DRAIN_FAILED",
                        unwrap(failure)
                );
            } finally {
                try {
                    journalSource.close();
                } catch (RuntimeException failure) {
                    reportFailure("JOURNAL_SOURCE_CLOSE_FAILED", unwrap(failure));
                } finally {
                    try {
                        statusSource.close();
                    } catch (RuntimeException failure) {
                        reportFailure(
                                "STATUS_SOURCE_CLOSE_FAILED",
                                unwrap(failure)
                        );
                    } finally {
                        try {
                            wiring.close();
                        } catch (RuntimeException failure) {
                            reportFailure(
                                    "KAIRON_RUNTIME_CLOSE_FAILED",
                                    unwrap(failure)
                            );
                        } finally {
                            stopped.countDown();
                        }
                    }
                }
            }
        }
    }

    private record SourceFailure(String code, Throwable failure) {

        private SourceFailure {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException(
                        "source failure code must not be blank"
                );
            }
            Objects.requireNonNull(failure, "failure");
        }
    }
}
