package com.ledger.eventservice.client;

import com.ledger.eventservice.exception.AccountServiceRejectedException;
import com.ledger.eventservice.exception.AccountServiceUnavailableException;
import com.ledger.eventservice.exception.ErrorResponse;
import com.ledger.eventservice.pojo.AccountTransactionRequest;
import com.ledger.eventservice.pojo.BalanceResponse;
import com.ledger.eventservice.pojo.EventRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
public class AccountServiceClient {

    private static final String CIRCUIT_BREAKER = "accountService";

    private final RestClient restClient;

    public AccountServiceClient(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
    }

    /**
     * Forwards the event to the Account Service as a transaction so the account balance is updated.
     * The Account Service is idempotent on {@code transactionId}, so a re-forwarded event is a no-op.
     *
     * @throws AccountServiceRejectedException    if the Account Service returns a 4xx (e.g. currency mismatch)
     * @throws AccountServiceUnavailableException if the Account Service is unreachable / failing / circuit is open
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER, fallbackMethod = "applyTransactionFallback")
    public void applyTransaction(EventRequest event) {
        AccountTransactionRequest accountTransactionRequest = AccountTransactionRequest.builder()
                .transactionId(event.getEventId())
                .type(event.getType())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .eventTimestamp(event.getEventTimestamp())
                .build();

        try {
            restClient.post()
                    .uri("/accounts/{accountId}/transactions", event.getAccountId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(accountTransactionRequest)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            throw rejected(e, "transaction for eventId=" + event.getEventId() + " accountId=" + event.getAccountId());
        } catch (RestClientException e) {
            throw unavailable(e, "apply transaction for eventId=" + event.getEventId());
        }
    }

    /**
     * Reads an account's balance from the Account Service (the Gateway's balance-query proxy).
     *
     * @throws AccountServiceRejectedException    if the Account Service returns a 4xx (e.g. 404 unknown account)
     * @throws AccountServiceUnavailableException if the Account Service is unreachable / failing / circuit is open
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER, fallbackMethod = "getBalanceFallback")
    public BalanceResponse getBalance(String accountId) {
        try {
            return restClient.get()
                    .uri("/accounts/{accountId}/balance", accountId)
                    .retrieve()
                    .body(BalanceResponse.class);
        } catch (HttpClientErrorException e) {
            throw rejected(e, "balance for accountId=" + accountId);
        } catch (RestClientException e) {
            throw unavailable(e, "get balance for accountId=" + accountId);
        }
    }

    // --- Circuit-breaker fallbacks: invoked on recorded failures and when the breaker is OPEN ---

    @SuppressWarnings("unused")
    private void applyTransactionFallback(EventRequest event, Throwable t) {
        throw toClientException(t);
    }

    @SuppressWarnings("unused")
    private BalanceResponse getBalanceFallback(String accountId, Throwable t) {
        throw toClientException(t);
    }

    private RuntimeException toClientException(Throwable t) {
        // 4xx business rejections propagate unchanged (e.g. 404 unknown account, 409 currency mismatch).
        if (t instanceof AccountServiceRejectedException rejected) {
            return rejected;
        }
        if (t instanceof AccountServiceUnavailableException unavailable) {
            return unavailable;
        }
        // CallNotPermittedException (breaker open) or anything else -> Account Service unavailable.
        return new AccountServiceUnavailableException("Account Service is unavailable", t);
    }

    private AccountServiceRejectedException rejected(HttpClientErrorException e, String what) {
        String message = extractMessage(e);
        log.warn("Account Service rejected {}: {} {}", what, e.getStatusCode(), message);
        return new AccountServiceRejectedException(e.getStatusCode().value(), message);
    }

    private AccountServiceUnavailableException unavailable(RestClientException e, String what) {
        log.error("Could not {} - Account Service unreachable: {}", what, e.getMessage());
        return new AccountServiceUnavailableException("Account Service is unavailable", e);
    }

    private String extractMessage(HttpClientErrorException e) {
        try {
            ErrorResponse errorResponse = e.getResponseBodyAs(ErrorResponse.class);
            if (errorResponse != null && errorResponse.getMessage() != null) {
                return errorResponse.getMessage();
            }
        } catch (Exception ignored) {
            // fall through to a generic message
        }
        return "Account Service rejected the request";
    }
}
