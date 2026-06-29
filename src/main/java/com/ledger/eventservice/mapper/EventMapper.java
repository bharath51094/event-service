package com.ledger.eventservice.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.eventservice.model.Event;
import com.ledger.eventservice.model.EventType;
import com.ledger.eventservice.pojo.EventRequest;
import com.ledger.eventservice.pojo.EventResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class EventMapper {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;

    public Event toEntity(EventRequest request) {
        return Event.builder()
                .eventId(request.getEventId())
                .accountId(request.getAccountId())
                .type(EventType.valueOf(request.getType()))
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .metadata(serializeMetadata(request.getMetadata()))
                .build();
    }

    public EventResponse toResponse(Event event) {
        return EventResponse.builder()
                .eventId(event.getEventId())
                .accountId(event.getAccountId())
                .type(event.getType().name())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .eventTimestamp(event.getEventTimestamp())
                .metadata(deserializeMetadata(event.getMetadata()))
//                .createdAt(event.getCreatedAt())
                .build();
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize metadata", e);
        }
    }

    private Map<String, Object> deserializeMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(metadata, METADATA_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to deserialize metadata", e);
        }
    }
}
