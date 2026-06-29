package com.ledger.eventservice.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Outbound payload sent to the Account Service's
 * {@code POST /accounts/{accountId}/transactions} endpoint. The {@code accountId}
 * travels in the URL path, so it is not part of this body.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountTransactionRequest {

    private String transactionId;
    private String type;
    private BigDecimal amount;
    private String currency;
    private OffsetDateTime eventTimestamp;
}
