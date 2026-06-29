package com.ledger.eventservice.client;

import com.ledger.eventservice.exception.AccountServiceRejectedException;
import com.ledger.eventservice.exception.AccountServiceUnavailableException;
import com.ledger.eventservice.exception.ErrorResponse;
import com.ledger.eventservice.pojo.AccountTransactionRequest;
import com.ledger.eventservice.pojo.EventRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
public class AccountServiceClient {

    private final RestClient restClient;

    public AccountServiceClient(RestClient accountServiceRestClient) {
        this.restClient = accountServiceRestClient;
    }

    /**
     * Forwards the event to the Account Service as a transaction so the account balance is updated.
     * The Account Service is idempotent on {@code transactionId}, so a re-forwarded event is a no-op.
     *
     * @throws AccountServiceUnavailableException if the Account Service cannot be reached / fails
     */
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
            // Account Service rejected the request for a business reason (e.g. currency mismatch).
            String message = extractMessage(e);
            log.warn("Account Service rejected transaction for eventId={} accountId={}: {} {}",
                    event.getEventId(), event.getAccountId(), e.getStatusCode(), message);
            throw new AccountServiceRejectedException(e.getStatusCode().value(), message);
        } catch (RestClientException e) {
            // Connection failure / timeout / 5xx -> the Account Service is unavailable.
            log.error("Failed to reach Account Service for eventId={} accountId={}: {}",
                    event.getEventId(), event.getAccountId(), e.getMessage());
            throw new AccountServiceUnavailableException(
                    "Account Service is unavailable; transaction could not be applied", e);
        }
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
        return "Account Service rejected the transaction";
    }
}
