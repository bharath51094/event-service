package com.ledger.eventservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only Actuator endpoint ({@code GET /eventoutcomes}) that renders the {@code ledger.events.processed}
 * counter as a per-{@code type}×{@code outcome} breakdown, each row carrying its count and a plain-English
 * sentence. It exists because the built-in {@code /metrics/{name}} endpoint only returns an aggregated total
 * (it cannot enumerate per-tag combinations), and Micrometer carries only one description per metric name —
 * neither can show "count per outcome, with what the outcome means".
 *
 * <p>It reads the existing counters from the {@link MeterRegistry}; it records nothing and does not change
 * the metric.
 */
@Component
@Endpoint(id = "eventoutcomes")
@RequiredArgsConstructor
public class EventOutcomeMetricsEndpoint {

    private static final String EVENTS_PROCESSED_METRIC = "ledger.events.processed";

    private final MeterRegistry meterRegistry;

    @ReadOperation
    public Map<String, Object> eventOutcomes() {
        List<Map<String, Object>> outcomes = meterRegistry.find(EVENTS_PROCESSED_METRIC).counters().stream()
                .sorted(Comparator
                        .comparing((Counter counter) -> tagOrEmpty(counter, "type"))
                        .thenComparing(counter -> tagOrEmpty(counter, "outcome")))
                .map(this::toRow)
                .toList();

        long totalCount = outcomes.stream().mapToLong(row -> (long) row.get("count")).sum();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metric", EVENTS_PROCESSED_METRIC);
        response.put("totalCount", totalCount);
        response.put("outcomes", outcomes);
        return response;
    }

    private Map<String, Object> toRow(Counter counter) {
        String type = tagOrEmpty(counter, "type");
        String outcomeTag = tagOrEmpty(counter, "outcome");
        long count = (long) counter.count();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("outcome", outcomeTag);
        row.put("count", count);
        row.put("description", describe(count, type, outcomeTag));
        return row;
    }

    private String describe(long count, String type, String outcomeTag) {
        EventOutcome outcome = EventOutcome.fromTag(outcomeTag);
        String fragment = outcome != null ? outcome.getSentenceFragment() : "had outcome '" + outcomeTag + "'";
        return "%d %s event(s) %s.".formatted(count, type, fragment);
    }

    private static String tagOrEmpty(Counter counter, String key) {
        String value = counter.getId().getTag(key);
        return value != null ? value : "";
    }
}
