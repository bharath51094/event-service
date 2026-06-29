package com.ledger.eventservice.controller;

import com.ledger.eventservice.client.AccountServiceClient;
import com.ledger.eventservice.pojo.BalanceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public-facing balance query. The Account Service is internal, so the Gateway proxies the
 * balance read to it — returning 404 for an unknown account and 503 when it is unreachable.
 */
@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@Slf4j
public class AccountQueryController {

    private final AccountServiceClient accountServiceClient;

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable("accountId") String accountId) {
        log.info("Balance query (proxy) accountId={}", accountId);
        return ResponseEntity.ok(accountServiceClient.getBalance(accountId.trim()));
    }
}
