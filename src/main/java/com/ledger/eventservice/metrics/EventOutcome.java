package com.ledger.eventservice.metrics;

import lombok.Getter;

/**
 * The possible outcomes of an event-create attempt, recorded as the {@code outcome} tag on the
 * {@code ledger.events.processed} metric. Each value pairs the terse machine tag (kept stable for the
 * metric series) with a human-readable sentence fragment used by {@link EventOutcomeMetricsEndpoint} to
 * explain what the short form means.
 */
@Getter
public enum EventOutcome {

    CREATED("created", "were newly created and stored"),
    DUPLICATE("duplicate", "were ignored as duplicates (an event with the same eventId was already processed)"),
    REJECTED("rejected", "were rejected by the Account Service (a business rule failed, e.g. currency mismatch)"),
    UNAVAILABLE("unavailable", "could not be processed because the Account Service was unavailable");

    /** Stable, machine-friendly value emitted as the {@code outcome} metric tag. */
    private final String tag;
    /** Human-readable clause completing the sentence "<count> <type> event(s) ...". */
    private final String sentenceFragment;

    EventOutcome(String tag, String sentenceFragment) {
        this.tag = tag;
        this.sentenceFragment = sentenceFragment;
    }

    /**
     * Resolves an {@code outcome} tag value back to its enum constant. Returns {@code null} for an
     * unrecognized tag so callers can fall back to a generic description rather than fail.
     */
    public static EventOutcome fromTag(String tag) {
        for (EventOutcome outcome : values()) {
            if (outcome.tag.equals(tag)) {
                return outcome;
            }
        }
        return null;
    }
}
