package com.ledger.eventservice.controller;

import com.ledger.eventservice.pojo.EventRequest;
import com.ledger.eventservice.pojo.EventResponse;
import com.ledger.eventservice.service.EventCreationResult;
import com.ledger.eventservice.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody EventRequest request) {
        log.info("Received event submission eventId={} accountId={}", request.getEventId(), request.getAccountId());
        EventCreationResult eventCreationResult = eventService.createEvent(request);
        EventResponse eventResponse = eventCreationResult.event();

        if (eventCreationResult.created()) {
            URI location = URI.create("/events/" + eventResponse.getEventId());
            return ResponseEntity.created(location).body(eventResponse);
        }

        return ResponseEntity.ok(eventResponse);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable("id") String eventId) {
        return ResponseEntity.ok(eventService.getEventByEventId(eventId));
    }

    @GetMapping
    public ResponseEntity<List<EventResponse>> listEvents(@RequestParam("account") String accountId) {
        return ResponseEntity.ok(eventService.listEventsByAccount(accountId));
    }
}
