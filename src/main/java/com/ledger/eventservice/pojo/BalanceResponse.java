package com.ledger.eventservice.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Balance read back from the Account Service and returned to the client by the Gateway's
 * balance-proxy endpoint.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BalanceResponse {

    private String accountId;
    private BigDecimal balance;
    private String currency;
}
