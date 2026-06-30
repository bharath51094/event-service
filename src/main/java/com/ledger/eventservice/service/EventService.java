package com.ledger.eventservice.service;

import com.ledger.eventservice.client.AccountServiceClient;
import com.ledger.eventservice.exception.EventNotFoundException;
import com.ledger.eventservice.mapper.EventMapper;
import com.ledger.eventservice.metrics.TrackEventOutcome;
import com.ledger.eventservice.model.Event;
import com.ledger.eventservice.pojo.EventRequest;
import com.ledger.eventservice.pojo.EventResponse;
import com.ledger.eventservice.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final AccountServiceClient accountServiceClient;

    @TrackEventOutcome
    public EventCreationResult createEvent(EventRequest request) {
        Optional<Event> existingEvent = eventRepository.findByEventId(request.getEventId());
        if (existingEvent.isPresent()) {
            // Already stored (and already forwarded when first created) — idempotent, do not re-apply.
            log.info("Duplicate event, returning existing (idempotent) eventId={}", request.getEventId());
            return new EventCreationResult(eventMapper.toResponse(existingEvent.get()), false);
        }

        // Apply the transaction on the Account Service first. If it is unavailable this throws
        // AccountServiceUnavailableException (-> 503) and the event is NOT stored, so a retry is clean.
        log.info("Forwarding transaction to Account Service eventId={} accountId={}",
                request.getEventId(), request.getAccountId());
        accountServiceClient.applyTransaction(request);

        try {
            Event savedEvent = eventRepository.save(eventMapper.toEntity(request));
            log.info("Stored event eventId={} accountId={}", savedEvent.getEventId(), savedEvent.getAccountId());
            return new EventCreationResult(eventMapper.toResponse(savedEvent), true);
        } catch (DataIntegrityViolationException e) {
            // Concurrent submission won the race on the unique eventId constraint;
            // fetch and return the persisted event so the call stays idempotent.
            Event raceWinner = eventRepository.findByEventId(request.getEventId())
                    .orElseThrow(() -> e);
            log.info("Concurrent create race, returning existing (idempotent) eventId={}", request.getEventId());
            return new EventCreationResult(eventMapper.toResponse(raceWinner), false);
        }
    }

    @Transactional(readOnly = true)
    public EventResponse getEventByEventId(String eventId) {
        return eventRepository.findByEventId(eventId)
                .map(eventMapper::toResponse)
                .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));
    }

    @Transactional(readOnly = true)
    public List<EventResponse> listEventsByAccount(String accountId) {
        return eventRepository.findAllByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(eventMapper::toResponse)
                .toList();
    }
}
