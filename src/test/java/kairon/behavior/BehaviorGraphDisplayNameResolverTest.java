package kairon.behavior;

import kairon.behavior.graph.BehaviorGraphDisplayNameResolver;
import kairon.behavior.normalize.NormalizedEventType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BehaviorGraphDisplayNameResolverTest {

    private final BehaviorGraphDisplayNameResolver resolver =
            new BehaviorGraphDisplayNameResolver();

    @Test
    void convertsTaskExamplesToReadableEnglish() {
        assertEquals(
                "FSS Discovery Scan",
                resolver.resolve(NormalizedEventType.FSS_DISCOVERY_SCAN)
        );
        assertEquals(
                "Scan Organic Analyse",
                resolver.resolve(
                        NormalizedEventType.SCAN_ORGANIC_ANALYSE
                )
        );
    }

    @Test
    void preservesKnownInitialismsAndFormatsUnknownCanonicalValues() {
        assertEquals(
                "PVP Combat 2",
                resolver.resolve(NormalizedEventType.of("PVP_COMBAT_2"))
        );
        assertEquals(
                "Unknown Future Event",
                resolver.resolve(
                        NormalizedEventType.of("UNKNOWN_FUTURE_EVENT")
                )
        );
    }
}
