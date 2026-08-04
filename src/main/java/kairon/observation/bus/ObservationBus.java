package kairon.observation.bus;

import kairon.observation.ObservationDraft;
import kairon.observation.ObservationPayload;
import kairon.observation.PublishedObservation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * In-process typed transport for externally observed data.
 */
public interface ObservationBus extends AutoCloseable {

    <T extends ObservationPayload> ObservationSubscription subscribe(
            String subscriberId,
            Class<T> payloadType,
            ObservationHandler<T> handler
    );

    <T extends ObservationPayload> CompletionStage<PublishReceipt> publish(
            ObservationDraft<T> observation
    );

    CompletionStage<Void> drainAndClose();

    @Override
    void close();

    @FunctionalInterface
    interface ObservationHandler<T extends ObservationPayload> {

        void onObservation(PublishedObservation<T> observation);
    }

    interface ObservationSubscription extends AutoCloseable {

        String subscriberId();

        boolean isActive();

        @Override
        void close();
    }

    record PublishReceipt(
            String observationId,
            long busSequence,
            List<String> matchedSubscriberIds,
            List<String> failedSubscriberIds
    ) {

        public PublishReceipt {
            observationId = requireNonBlank(observationId, "observationId");
            if (busSequence < 1) {
                throw new IllegalArgumentException("busSequence must be positive");
            }
            matchedSubscriberIds = copySubscriberIds(
                    matchedSubscriberIds,
                    "matchedSubscriberIds"
            );
            failedSubscriberIds = copySubscriberIds(
                    failedSubscriberIds,
                    "failedSubscriberIds"
            );
            requireOrderedSubset(matchedSubscriberIds, failedSubscriberIds);
        }

        private static List<String> copySubscriberIds(List<String> ids, String name) {
            Objects.requireNonNull(ids, name);
            Set<String> uniqueIds = new HashSet<>();
            for (String id : ids) {
                requireNonBlank(id, name + " entry");
                if (!uniqueIds.add(id)) {
                    throw new IllegalArgumentException(name + " must not contain duplicates");
                }
            }
            return List.copyOf(ids);
        }

        private static void requireOrderedSubset(List<String> matched, List<String> failed) {
            int previousIndex = -1;
            for (String failedId : failed) {
                int matchedIndex = matched.indexOf(failedId);
                if (matchedIndex <= previousIndex) {
                    throw new IllegalArgumentException(
                            "failedSubscriberIds must be an ordered subset "
                                    + "of matchedSubscriberIds"
                    );
                }
                previousIndex = matchedIndex;
            }
        }

        private static String requireNonBlank(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
