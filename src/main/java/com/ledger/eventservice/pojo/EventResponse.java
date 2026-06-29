package com.ledger.eventservice.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventResponse {

    private String eventId;
    private String accountId;
    private String type;
    private BigDecimal amount;
    private String currency;
    private OffsetDateTime eventTimestamp;
    private Map<String, Object> metadata;
}
