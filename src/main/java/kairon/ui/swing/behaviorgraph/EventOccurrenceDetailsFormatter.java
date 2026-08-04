package kairon.ui.swing.behaviorgraph;

import com.fasterxml.jackson.databind.JsonNode;
import kairon.behavior.graph.EventOccurrenceDetailsSnapshot;
import kairon.behavior.model.ContextSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic English formatter for one occurrence details snapshot.
 */
final class EventOccurrenceDetailsFormatter {

    String format(EventOccurrenceDetailsSnapshot details) {
        Objects.requireNonNull(details, "details");
        StringBuilder output = new StringBuilder();
        output.append("Identity\n\n");
        property(output, "Event type", details.eventType().value());
        property(output, "Source event", details.originalEventName());
        property(output, "Timestamp", details.timestamp());
        property(output, "Episode sequence", details.episodeSequence());
        property(output, "Source sequence", details.sourceSequence());
        property(output, "Source ID", details.sourceId());
        property(output, "Occurrence ID", details.occurrenceId());
        property(output, "Episode ID", details.episodeId());
        property(output, "Graph ID", details.graphId().canonicalValue());

        output.append("\nEvent Attributes\n\n");
        if (details.attributes().isEmpty()) {
            output.append("(none)\n");
        } else {
            for (Map.Entry<String, JsonNode> attribute
                    : details.attributes().entrySet()) {
                output.append(attribute.getKey())
                        .append(": ")
                        .append(formatJson(attribute.getValue(), 0))
                        .append('\n');
            }
        }

        output.append("\nContext\n\n");
        appendContext(output, details.context());
        return output.toString();
    }

    private static void appendContext(
            StringBuilder output,
            ContextSnapshot context
    ) {
        property(output, "commanderFid", context.commanderFid());
        property(output, "shipId", context.shipId());
        property(output, "shipType", context.shipType());
        property(output, "shipName", context.shipName());
        property(output, "loadoutHash", context.loadoutHash());
        property(output, "systemAddress", context.systemAddress());
        property(output, "systemName", context.systemName());
        property(output, "bodyId", context.bodyId());
        property(output, "bodyName", context.bodyName());
        property(output, "bodyType", context.bodyType());
        property(output, "commanderMode", context.commanderMode());
        property(output, "flightMode", context.flightMode());
        property(output, "vehicleKind", context.vehicleKind());
        property(
                output,
                "biologicalSignalCount",
                context.biologicalSignalCount()
        );
        property(
                output,
                "geologicalSignalCount",
                context.geologicalSignalCount()
        );
        property(output, "landable", context.landable());
        property(output, "wasDiscovered", context.wasDiscovered());
        property(output, "wasMapped", context.wasMapped());
        property(output, "wasFootfalled", context.wasFootfalled());
        property(
                output,
                "distanceFromArrivalLs",
                context.distanceFromArrivalLs()
        );
        property(output, "bodyHasBiology", context.bodyHasBiology());
        property(
                output,
                "activeOrganicSampling",
                context.activeOrganicSampling()
        );
    }

    private static void property(
            StringBuilder output,
            String name,
            Object value
    ) {
        output.append(name)
                .append(": ")
                .append(value == null ? "null" : value)
                .append('\n');
    }

    private static String formatJson(JsonNode node, int indentation) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.toString();
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return "[]";
            }
            List<String> elements = new ArrayList<>();
            node.forEach(element ->
                    elements.add(formatJson(element, indentation + 2)));
            return multilineCollection(
                    "[",
                    "]",
                    elements,
                    indentation
            );
        }
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                fields.add(name + ": " + formatJson(
                        node.get(name),
                        indentation + 2
                ));
            }
            return multilineCollection(
                    "{",
                    "}",
                    fields,
                    indentation
            );
        }
        return node.toString();
    }

    private static String multilineCollection(
            String opening,
            String closing,
            List<String> values,
            int indentation
    ) {
        String childIndent = " ".repeat(indentation + 2);
        String closingIndent = " ".repeat(indentation);
        return opening
                + '\n'
                + childIndent
                + String.join(",\n" + childIndent, values)
                + '\n'
                + closingIndent
                + closing;
    }
}
