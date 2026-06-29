package com.ledger.eventservice.service;

import com.ledger.eventservice.pojo.EventResponse;

/**
 * Result of a create-event attempt.
 *
 * @param event   the event as stored (newly created or the pre-existing one)
 * @param created {@code true} if a new event was persisted, {@code false} if an
 *                event with the same eventId already existed (idempotent replay)
 */
public record EventCreationResult(EventResponse event, boolean created) {
}
